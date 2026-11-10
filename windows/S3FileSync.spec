# -*- mode: python ; coding: utf-8 -*-
import os

# 该 spec 放在 Windows 源码根目录（最终仓库里的 windows/ 子目录），
# 所有路径相对 SPEC 所在目录，保证换位置也能构建。
APP_ROOT = os.path.dirname(os.path.abspath(SPEC))

a = Analysis(
    [os.path.join(APP_ROOT, 'run.pyw')],
    pathex=[APP_ROOT],
    binaries=[],
    datas=[
        (os.path.join(APP_ROOT, 'app', 'icon.ico'), 'app'),
    ],
    hiddenimports=[
        # 七牛 SDK 及其子模块（部分在运行时以字符串形式 import）
        'qiniu', 'qiniu.http', 'qiniu.utils',
        'qiniu.services', 'qiniu.services.auth', 'qiniu.services.storage',
        # 托盘图标（Windows 走 _win32 后端，依赖 pywin32）
        'pystray', 'pystray._win32',
        'pywin32', 'win32api', 'win32gui', 'win32con',
        # 其它运行时依赖
        'PIL', 'PIL._tkinter_finder',
        'requests', 'tkinter', 'tkinter.ttk',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=None,
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='S3FileSync',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=os.path.join(APP_ROOT, 'app', 'icon.ico'),
)
