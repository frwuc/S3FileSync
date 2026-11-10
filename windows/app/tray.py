# -*- coding: utf-8 -*-
"""托盘图标（pystray）。

注意线程模型：托盘运行在独立线程，菜单回调只允许向事件队列投递消息，
绝不允许直接操作 tkinter 控件。
"""
import pystray
from PIL import Image, ImageDraw

from app.config import APP_NAME


def _make_image():
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 云 + 上下双向箭头（上传 / 下载）
    d.ellipse([2, 12, 16, 26], fill=(37, 99, 235))
    d.ellipse([10, 6, 24, 20], fill=(37, 99, 235))
    d.rectangle([8, 16, 22, 24], fill=(37, 99, 235))
    d.polygon([(11, 20), (13, 14), (15, 20), (14, 20), (14, 24), (12, 24), (12, 20)],
              fill=(255, 255, 255))
    d.polygon([(18, 18), (20, 18), (20, 23), (22, 23), (19, 28), (16, 23), (18, 23)],
              fill=(255, 255, 255))
    return img


def make_icon(post_event, is_paused):
    """创建托盘图标。

    Args:
        post_event: callable(str) —— 回调里只能调用它投递事件
        is_paused: callable() -> bool —— 查询暂停状态（线程安全读）
    Returns:
        pystray.Icon（未启动；由调用方在独立线程 run）
    """

    def act(name):
        def handler(*_a):
            post_event(name)
        return handler

    menu = pystray.Menu(
        pystray.MenuItem('打开主窗口', act('show'), default=True),
        pystray.MenuItem('立即同步（按设置的方向）', act('sync_now')),
        pystray.MenuItem('仅上传一轮', act('sync_upload')),
        pystray.MenuItem('仅下载一轮', act('sync_download')),
        pystray.MenuItem('暂停自动同步', act('toggle_pause'),
                         checked=lambda _item: is_paused()),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem('打开本地目录', act('open_dir')),
        pystray.MenuItem('打开云端目录（七牛云）', act('open_cloud')),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem('退出', act('quit')),
    )
    icon = pystray.Icon('s3filesync', _make_image(), APP_NAME, menu)
    return icon
