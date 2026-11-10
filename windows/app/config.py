# -*- coding: utf-8 -*-
"""配置读写（%APPDATA%\\S3FileSync\\config.json）。

显示的软件名称由 APP_NAME 统一控制。
首次启动会把旧目录 qiniu-clockin-sync 整体迁移到 S3FileSync，
保留已有的 AK/SK 与同步状态（见 migrate_legacy_dir）。
"""
import json
import logging
import os
import shutil

log = logging.getLogger('s3filesync.config')

APP_NAME = 'S3FileSync'
APP_VERSION = '1.0.2'

# 后端说明：当前仅七牛云 Kodo 经过实测；其余 S3 兼容服务商尚未验证。
BACKEND_NOTE = '当前后端：七牛云 Kodo（S3 兼容）；其他 S3 兼容服务商暂未验证'

APP_DIR = os.path.join(
    os.environ.get('APPDATA') or os.path.expanduser('~'),
    'S3FileSync',
)
CONFIG_PATH = os.path.join(APP_DIR, 'config.json')

# 旧版本遗留的配置目录（项目曾用名 qiniu-clockin-sync）；首次启动整体搬过来。
LEGACY_APP_DIR = os.path.join(
    os.environ.get('APPDATA') or os.path.expanduser('~'),
    'qiniu-clockin-sync',
)


def migrate_legacy_dir():
    """把旧配置目录 qiniu-clockin-sync 整体迁移到 S3FileSync（仅首次）。

    迁移失败（如跨盘/权限）时退化为复制，不丢数据；若新目录已存在则跳过，
    避免覆盖已有配置。
    """
    old, new = LEGACY_APP_DIR, APP_DIR
    if old == new or not os.path.isdir(old) or os.path.exists(new):
        return
    try:
        shutil.move(old, new)
        log.info('已将旧配置目录迁移到 %s', new)
    except Exception as e:  # 移动失败则复制兜底
        try:
            shutil.copytree(old, new)
            log.warning('移动旧配置目录失败（%s），已复制兜底', e)
        except Exception as e2:
            log.warning('迁移旧配置目录失败: %s', e2)

# 同步方向：download=云端→本地，upload=本地→云端，both=双向
SYNC_MODES = {
    'download': '仅下载（云端 → 本地）',
    'upload': '仅上传（本地 → 云端）',
    'both': '双向同步（上传 + 下载）',
}
MODE_BY_LABEL = {v: k for k, v in SYNC_MODES.items()}

DEFAULTS = {
    'ak': '',
    'sk': '',
    'bucket': '',
    'region': 'z0',            # z0 / cn-east-2 / z1 / z2 / na0 / as0
    'download_domain': '',     # 桶绑定域名；留空则自动获取
    'download_dir': '',        # 本地同步根目录（既是下载目标，也是上传来源）
    'interval_minutes': 30,    # 自动同步间隔
    'prefix': '',              # 同步的 key 前缀；留空 = 整个空间
    'sync_mode': 'download',   # download / upload / both
    'upload_overwrite': False, # 自动上传时是否覆盖云端同名但内容不同的对象
    'proxy_mode': 'auto',      # auto / direct / system，规避失效的本地代理
}


def load() -> dict:
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH, 'r', encoding='utf-8') as f:
            cfg.update(json.load(f))
    except (FileNotFoundError, json.JSONDecodeError):
        pass
    except Exception:
        pass
    if cfg.get('sync_mode') not in SYNC_MODES:
        cfg['sync_mode'] = 'download'
    return cfg


def save(cfg: dict) -> None:
    os.makedirs(APP_DIR, exist_ok=True)
    tmp = CONFIG_PATH + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    os.replace(tmp, CONFIG_PATH)


def is_ready(cfg: dict) -> bool:
    return bool(cfg.get('ak') and cfg.get('sk') and cfg.get('bucket')
                and cfg.get('download_dir'))
