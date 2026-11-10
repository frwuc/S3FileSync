# -*- coding: utf-8 -*-
"""同步引擎：支持三种方向（仅下载 / 仅上传 / 双向）。

本地根目录与云端 key 一一对应：
    云端 key  rtkq-data/报告.pdf  ⇄  本地 <根目录>/rtkq-data/报告.pdf
配置的「云端目录（前缀）」用于限定自动同步的范围；留空表示整个空间。
上传时按七牛 etag 比对云端同名对象，内容一致即跳过，避免重复传输。

CLI 调试:
    cd E:/agentwork/S3FileSync/windows
    python -m app.sync --once                    # 按配置方向同步一轮
    python -m app.sync --once --mode upload     # 仅上传一轮
    python -m app.sync --watch                  # 按配置间隔循环
"""
import argparse
import logging
import os
import re
import sys
import time

from app import config as config_mod
from app import state as state_mod
from app.qiniu_client import local_etag

log = logging.getLogger('clockin.sync')

_ILLEGAL = re.compile(r'[<>:"|?*\x00-\x1f]')
_SKIP_NAMES = {'thumbs.db', 'desktop.ini', '.ds_store'}


def sanitize_key(key: str) -> str:
    """把对象 key 转为 Windows 合法相对路径（逐段替换非法字符、去尾部空格与点）。"""
    parts = []
    for seg in key.split('/'):
        seg = _ILLEGAL.sub('_', seg).rstrip(' .')
        parts.append(seg or '_')
    return os.path.join(*parts)


def local_target(root: str, key: str) -> str:
    p = os.path.join(root, sanitize_key(key))
    ap = os.path.abspath(p)
    if len(ap) > 240:
        ap = '\\\\?\\' + ap
    return ap


def norm_prefix(prefix) -> str:
    """统一前缀格式：用 / 分隔，去掉首尾空白与开头的 /。"""
    return (prefix or '').strip().replace('\\', '/').lstrip('/')


def local_scope(root: str, prefix) -> str:
    """云端前缀对应的本地子目录（不做尾部补 '_'，与 sanitize_key 不同）。"""
    parts = [p for p in norm_prefix(prefix).split('/') if p]
    if not parts:
        return root
    safe = [_ILLEGAL.sub('_', p).rstrip(' .') or '_' for p in parts]
    return os.path.join(root, *safe)


def _in_root(path: str, root: str) -> bool:
    """判断文件路径是否位于当前同步根目录内（跨盘符时返回 False）。"""
    try:
        return os.path.commonpath([os.path.abspath(path),
                                   os.path.abspath(root)]) == os.path.abspath(root)
    except (ValueError, OSError):
        return False


def iter_local_files(root: str, prefix):
    """产出 (云端 key, 本地绝对路径, 字节数)，key 保持与下载时一致。

    跳过临时/系统文件，以及旧版为云端目录占位对象生成的空 '_' 文件。
    """
    scope = local_scope(root, prefix)
    if not os.path.isdir(scope):
        log.info('本地同步目录不存在，跳过上传: %s', scope)
        return []
    out = []
    for dirpath, _dirnames, filenames in os.walk(scope):
        for name in filenames:
            path = os.path.join(dirpath, name)
            try:
                size = os.path.getsize(path)
            except OSError:
                continue
            low = name.lower()
            if low in _SKIP_NAMES or low.endswith('.part') or name.startswith('~$'):
                continue
            if name == '_' and size == 0:
                continue
            rel = os.path.relpath(path, root).replace(os.sep, '/')
            out.append((rel, path, size))
    out.sort(key=lambda x: x[0])
    return out


def _upload_phase(cfg, client, state, root, prefix, summary):
    """本地 → 云端。"""
    overwrite = bool(cfg.get('upload_overwrite', False))
    remote = {}
    for it in client.list_all(prefix):
        if it.get('key'):
            remote[it['key']] = it
    summary['total'] = len(remote)

    files = iter_local_files(root, prefix)
    summary['local_total'] = len(files)
    for key, path, size in files:
        try:
            h = local_etag(path)
        except Exception as e:
            summary['failed'].append((key, '无法计算本地校验值: {0}'.format(e)))
            continue
        cur = remote.get(key)
        if cur and cur.get('hash') == h:
            summary['skipped'] += 1
            continue
        if cur and not overwrite:
            summary['skipped'] += 1
            log.warning('云端已有同名且内容不同，已跳过: %s（可在设置页开启「上传覆盖同名」）', key)
            continue
        try:
            rh = client.upload_file(path, key)
            state_mod.record(state, key, rh, size, path)
            state_mod.save(state)
            summary['uploaded'].append(key)
            log.info('已上传: %s -> %s', path, key)
        except Exception as e:
            summary['failed'].append((key, str(e)))
            log.warning('上传失败: %s (%s)', key, e)


def _download_phase(cfg, client, state, root, prefix, summary):
    """云端 → 本地。"""
    items = client.list_all(prefix)
    summary['total'] = len(items)
    for it in items:
        key = it.get('key')
        etag = it.get('hash')
        fsize = it.get('fsize', 0)
        if not key:
            continue
        # 以 / 结尾的是目录占位对象，不是真实文件
        if key.endswith('/'):
            summary['skipped'] += 1
            continue
        dest = local_target(root, key)
        st = state.get(key)
        same_etag = bool(st and st.get('etag') == etag)
        if same_etag:
            # 状态里的本地文件必须仍位于当前根目录内：上传记录或换过本地目录时
            # 不能因为旧路径存在就跳过下载
            lp = st.get('local_path') or ''
            if lp and os.path.isfile(lp) and _in_root(lp, root):
                summary['skipped'] += 1
                continue
            if os.path.isfile(dest) and os.path.getsize(dest) == fsize:
                state_mod.record(state, key, etag, fsize, dest)
                state_mod.save(state)
                summary['skipped'] += 1
                continue
        try:
            # 本地已存在同大小文件且状态缺失（state 丢失场景）→ 只补状态不重下
            if not st and os.path.isfile(dest) and os.path.getsize(dest) == fsize:
                state_mod.record(state, key, etag, fsize, dest)
                state_mod.save(state)
                summary['skipped'] += 1
                continue
            # 本地同名但内容不同 → 冲突改名，不覆盖
            if os.path.exists(dest):
                base, ext = os.path.splitext(dest)
                i = 1
                while os.path.exists(dest):
                    dest = '{0}.conflict{1}{2}'.format(base, i, ext)
                    i += 1
            client.download(key, dest)
            state_mod.record(state, key, etag, fsize, dest)
            state_mod.save(state)
            summary['downloaded'].append(key)
            log.info('已下载: %s -> %s', key, dest)
        except Exception as e:
            summary['failed'].append((key, str(e)))
            log.warning('下载失败: %s (%s)', key, e)


def run_once(cfg: dict, client=None, mode=None) -> dict:
    """执行一轮同步，返回摘要。

    摘要字段: mode / total(云端对象数) / local_total(本地文件数) /
    uploaded / downloaded / new(=上传+下载) / failed / skipped / error
    """
    mode = mode or cfg.get('sync_mode', 'download')
    if mode not in config_mod.SYNC_MODES:
        mode = 'download'
    if client is None:
        from app.qiniu_client import QiniuKodo
        client = QiniuKodo(cfg['ak'], cfg['sk'], cfg['bucket'],
                           cfg.get('region', 'z0'), cfg.get('download_domain', ''),
                           cfg.get('proxy_mode', 'auto'))
    prefix = norm_prefix(cfg.get('prefix', ''))
    root = cfg.get('download_dir') or '.'
    os.makedirs(root, exist_ok=True)

    summary = {
        'mode': mode, 'total': 0, 'local_total': 0,
        'uploaded': [], 'downloaded': [], 'new': [],
        'failed': [], 'skipped': 0, 'error': None,
    }
    state = state_mod.load()
    errors = []
    log.info('同步方向: %s（云端目录: %s）',
             config_mod.SYNC_MODES[mode], prefix or '整个空间')

    # 双向时先上传再下载：上传写入的 state 会让下载阶段正确跳过刚上传的对象
    if mode in ('upload', 'both'):
        try:
            _upload_phase(cfg, client, state, root, prefix, summary)
        except Exception as e:
            log.exception('上传阶段失败')
            errors.append('上传: {0}'.format(e))
    if mode in ('download', 'both'):
        try:
            _download_phase(cfg, client, state, root, prefix, summary)
        except Exception as e:
            log.exception('下载阶段失败')
            errors.append('下载: {0}'.format(e))

    summary['new'] = summary['uploaded'] + summary['downloaded']
    summary['error'] = '; '.join(errors) or None
    # 上传时若发现配置区域与实际不符，带给 UI 层自动纠正
    detected = getattr(client, 'detected_region', None)
    if detected:
        summary['detected_region'] = detected
    state_mod.save(state)
    return summary


def _setup_console_logging():
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s %(levelname)s %(message)s',
        datefmt='%H:%M:%S',
    )


def _print_summary(s):
    print('同步完成[{0}]: 上传 {1} / 下载 {2} / 跳过 {3} / 失败 {4} / 云端 {5} / 本地 {6}'.format(
        config_mod.SYNC_MODES.get(s['mode'], s['mode']),
        len(s['uploaded']), len(s['downloaded']), s['skipped'],
        len(s['failed']), s['total'], s['local_total']))
    if s.get('error'):
        print('  阶段错误: {0}'.format(s['error']))
    for key, err in s['failed']:
        print('  失败: {0} ({1})'.format(key, err))


def main():
    _setup_console_logging()
    ap = argparse.ArgumentParser(description='七牛云文件同步（CLI）')
    ap.add_argument('--once', action='store_true', help='同步一轮后退出')
    ap.add_argument('--watch', action='store_true', help='按配置间隔循环同步')
    ap.add_argument('--mode', choices=list(config_mod.SYNC_MODES),
                    help='临时指定方向，覆盖配置里的同步方向')
    args = ap.parse_args()

    cfg = config_mod.load()
    if not config_mod.is_ready(cfg):
        print('配置不完整（ak/sk/bucket/download_dir），请先在 GUI 设置页填写，'
              '或编辑 {0}'.format(config_mod.CONFIG_PATH))
        sys.exit(2)

    if args.watch:
        interval = max(1, int(cfg.get('interval_minutes', 30))) * 60
        while True:
            _print_summary(run_once(cfg, mode=args.mode))
            time.sleep(interval)
    else:
        _print_summary(run_once(cfg, mode=args.mode))


if __name__ == '__main__':
    main()
