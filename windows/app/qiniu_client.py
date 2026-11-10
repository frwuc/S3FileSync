# -*- coding: utf-8 -*-
"""七牛 Kodo 操作封装（list / stat / delete / 私有下载 / 上传 / etag）。

网络说明:
  Windows 上常驻进程常会继承一个已失效的本地代理（例如 Clash 的 127.0.0.1:7890
  未启动），requests 默认读取系统代理会导致所有管理 API 与下载请求失败。
  因此这里按配置决定代理模式：
    auto   —— 先探测能否直连七牛，能则忽略系统代理，否则沿用系统代理（默认）
    direct —— 始终直连
    system —— 始终沿用系统 / 环境变量代理
  qiniu SDK 内部共用一个 requests.Session，需要同步设置它的 trust_env。
"""
import logging
import os
import re
import threading
from urllib.parse import quote

import requests
from qiniu import Auth, BucketManager
from qiniu.utils import etag as _qiniu_etag

log = logging.getLogger('clockin.net')

UP_HOSTS = {
    'z0': 'https://up-z0.qiniup.com',
    'cn-east-2': 'https://up-cn-east-2.qiniup.com',
    'z1': 'https://up-z1.qiniup.com',
    'z2': 'https://up-z2.qiniup.com',
    'na0': 'https://up-na0.qiniup.com',
    'as0': 'https://up-as0.qiniup.com',
}

PROXY_MODES = {'auto': '自动（推荐）', 'direct': '直连', 'system': '系统代理'}

_NET_LOCK = threading.Lock()
_NET_MODE = None          # 已生效的模式缓存
_NET_DIRECT = True        # 当前是否直连

_UP_HOST_RE = re.compile(r'up-[a-z0-9-]+\.(?:qiniup\.com|qbox\.me)')
_UPHOST_LOCK = threading.Lock()
_UPHOST_CACHE = {}        # (ak, bucket) -> 上传域名，来自 UC 查询


class QiniuError(Exception):
    pass


def _err(info) -> str:
    return str(getattr(info, 'error', None) or info)


def local_etag(path: str) -> str:
    """计算本地文件的七牛 etag（与 list/stat 返回的 hash 可直接比对）。"""
    return _qiniu_etag(path)


def _host_to_region(host: str):
    m = re.match(r'https?://up-([a-z0-9-]+)\.', host or '')
    return m.group(1) if m else None


def query_up_host(ak, bucket, session, timeout=8):
    """向 UC 查询空间真实上传域名（配置里的区域可能填错，管理 API 却能正常用）。"""
    ck = (ak, bucket)
    with _UPHOST_LOCK:
        if ck in _UPHOST_CACHE:
            return _UPHOST_CACHE[ck]
    try:
        r = session.get('https://uc.qbox.me/v1/query',
                        params={'ak': ak, 'bucket': bucket}, timeout=timeout)
        try:
            if r.status_code // 100 == 2:
                hosts = (r.json().get('https') or {}).get('up') or []
                host = next((h for h in hosts if h.startswith('https://')), None)
                if host:
                    with _UPHOST_LOCK:
                        _UPHOST_CACHE[ck] = host
                    return host
        finally:
            r.close()
    except Exception as e:
        log.debug('查询上传域名失败，回退区域映射: %s', e)
    return None


def _probe_direct(timeout=2.5) -> bool:
    """不读任何代理配置，测试能否直连七牛（任何 HTTP 响应都视为连通）。"""
    s = requests.Session()
    s.trust_env = False
    try:
        s.get('https://uc.qbox.me', timeout=timeout)
        return True
    except Exception:
        return False
    finally:
        s.close()


def apply_network_mode(mode='auto') -> bool:
    """按模式配置 SDK 会话，返回是否使用直连。进程内相同模式只配置一次。"""
    global _NET_MODE, _NET_DIRECT
    mode = mode if mode in PROXY_MODES else 'auto'
    with _NET_LOCK:
        if _NET_MODE == mode:
            return _NET_DIRECT
        direct = mode == 'direct'
        if mode == 'auto':
            direct = _probe_direct()
        try:
            import qiniu.http as qhttp
            qhttp._init()                       # 确保 SDK 的全局 session 已建立
            sess = qhttp._session or getattr(qhttp.qn_http_client, 'session', None)
            if sess is not None:
                sess.trust_env = not direct    # 直连时忽略系统代理
        except Exception as e:
            log.warning('配置七牛 SDK 网络会话失败: %s', e)
        _NET_DIRECT = direct
        _NET_MODE = mode
        log.info('七牛网络模式: %s -> %s', PROXY_MODES[mode],
                 '直连（忽略系统代理）' if direct else '沿用系统代理')
        return direct


class QiniuKodo:
    def __init__(self, ak, sk, bucket, region, download_domain='', proxy_mode='auto'):
        self.auth = Auth(ak, sk)
        self.bm = BucketManager(self.auth)
        self.ak = ak
        self.bucket = bucket
        self.region = region
        self.download_domain = (download_domain or '').strip().rstrip('/')
        self.direct = apply_network_mode(proxy_mode)
        self.session = requests.Session()
        self.session.trust_env = not self.direct
        self.up_host = UP_HOSTS.get(region) or 'https://up.qiniup.com'
        self.detected_region = None
        self._up_resolved = False

    def _resolve_upload_host(self):
        """优先用 UC 查询到的真实上传域名；失败则按配置区域映射。"""
        host = query_up_host(self.ak, self.bucket, self.session)
        if host:
            self.up_host = host
            reg = _host_to_region(host)
            if reg and reg != self.region:
                self.detected_region = reg
                log.warning('空间 %s 实际存储区域为 %s（当前设置为 %s），上传端点已自动切换',
                            self.bucket, reg, self.region)
        return self.up_host


    # ---------- 列举 ----------

    def list_page(self, prefix='', marker=None, limit=100):
        """返回 (items, next_marker, eof)。items 为 dict 列表（key/hash/fsize/putTime）。"""
        ret, eof, info = self.bm.list(self.bucket, prefix=prefix, marker=marker, limit=limit)
        if not info.ok():
            raise QiniuError('列举失败: {0}'.format(_err(info)))
        items = (ret or {}).get('items', [])
        next_marker = (ret or {}).get('marker') or None
        return items, next_marker, eof

    def list_all(self, prefix=''):
        items = []
        marker = None
        while True:
            page, marker, _eof = self.list_page(prefix, marker)
            items.extend(page)
            if not marker:
                break
        return items

    # ---------- 元信息 / 删除 ----------

    def stat(self, key):
        ret, info = self.bm.stat(self.bucket, key)
        if not info.ok():
            raise QiniuError('stat {0} 失败: {1}'.format(key, _err(info)))
        return ret

    def delete(self, key):
        _ret, info = self.bm.delete(self.bucket, key)
        if not info.ok():
            raise QiniuError('删除 {0} 失败: {1}'.format(key, _err(info)))

    # ---------- 下载 ----------

    def domains(self):
        """返回桶绑定的域名列表。"""
        res, info = self.bm.list_domains(self.bucket)
        if not info.ok():
            raise QiniuError('获取桶域名失败: {0}'.format(_err(info)))
        return res or []

    def resolve_domain(self) -> str:
        if self.download_domain:
            return self.download_domain
        ds = self.domains()
        if not ds:
            raise QiniuError('未配置下载域名，且该桶未绑定任何域名（请到七牛控制台查看空间域名）')
        self.download_domain = ds[0]
        return ds[0]

    def private_url(self, key, expires=3600) -> str:
        domain = self.resolve_domain()
        if not domain.startswith(('http://', 'https://')):
            domain = 'http://' + domain
        # 中文 key 必须 UTF-8 percent-encode 后再参与签名
        base = domain + '/' + quote(key, safe='/')
        return self.auth.private_download_url(base, expires)

    def probe_private_url(self, key, expires=600, timeout=30) -> int:
        """测试私有下载链接，返回 HTTP 状态码。"""
        url = self.private_url(key, expires)
        r = self.session.get(url, stream=True, timeout=timeout)
        try:
            return r.status_code
        finally:
            r.close()

    def download(self, key, dest, expires=3600) -> str:
        url = self.private_url(key, expires)
        r = self.session.get(url, stream=True, timeout=120)
        try:
            r.raise_for_status()
            d = os.path.dirname(dest)
            if d:
                os.makedirs(d, exist_ok=True)
            part = dest + '.part'
            try:
                with open(part, 'wb') as f:
                    for chunk in r.iter_content(64 * 1024):
                        if chunk:
                            f.write(chunk)
                os.replace(part, dest)
            finally:
                if os.path.exists(part):
                    try:
                        os.remove(part)
                    except OSError:
                        pass
        finally:
            r.close()
        return dest

    # ---------- 上传 ----------

    def upload_file(self, local_path, key, timeout=180) -> str:
        """表单上传一个本地文件到指定 key，返回云端 hash。

        使用本实例自己的 requests.Session，因此同样遵守 auto/direct/system
        网络模式，不会被失效的本地代理拖垮。允许覆盖同名对象；是否覆盖由
        同步引擎根据 etag 与配置决定，这里只负责传输。

        上传端点先由 UC 查询空间真实区域（配置里的 region 可能填错），
        若仍收到 incorrect region 报错，会解析出正确域名再重试一次。
        """
        if not self._up_resolved:
            self._resolve_upload_host()
            self._up_resolved = True
        filename = key.rsplit('/', 1)[-1] or 'file'
        for attempt in range(2):
            token = self.auth.upload_token(self.bucket, key, 3600)
            with open(local_path, 'rb') as fp:
                r = self.session.post(
                    self.up_host + '/',
                    data={'token': token, 'key': key},
                    files={'file': (filename, fp, 'application/octet-stream')},
                    timeout=timeout,
                )
            try:
                try:
                    ret = r.json()
                except ValueError:
                    ret = {}
                err = ret.get('error') or ''
                if r.status_code == 400 and 'incorrect region' in err and attempt == 0:
                    m = _UP_HOST_RE.search(err)
                    if m:
                        self.up_host = 'https://' + m.group(0)
                        reg = _host_to_region(self.up_host)
                        if reg and reg != self.region:
                            self.detected_region = reg
                            log.warning('上传区域不符，已自动切换到 %s；'
                                        '建议把设置页「存储区域」改为 %s',
                                        self.up_host, reg)
                        continue
                if r.status_code // 100 != 2 or err:
                    raise QiniuError('上传 {0} 失败: HTTP {1} {2}'.format(
                        key, r.status_code, err or r.text[:160]))
                return ret.get('hash') or local_etag(local_path)
            finally:
                r.close()
        raise QiniuError('上传 {0} 失败: 区域自动切换后仍失败'.format(key))

    # ---------- 表单上传（自检用，token 由调用方生成） ----------

    def form_upload(self, token, key, data: bytes, host=None, filename='test.jpg', mime='image/jpeg'):
        host = host or UP_HOSTS.get(self.region)
        if not host:
            raise QiniuError('未知区域: {0}'.format(self.region))
        r = self.session.post(
            host + '/',
            data={'token': token, 'key': key},
            files={'file': (filename, data, mime)},
            timeout=60,
        )
        return r
