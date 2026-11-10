# -*- coding: utf-8 -*-
"""设置页：连接信息 + 同步方向/范围 + 本地与云端目录快捷打开 + 自检。"""
import os
import sys
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

try:
    import winreg
except ImportError:  # 非 Windows
    winreg = None

from app import config
from app.gui import theme
from app.qiniu_client import UP_HOSTS, PROXY_MODES



RUN_KEY = r'Software\Microsoft\Windows\CurrentVersion\Run'
VALUE_NAME = 'S3FileSync'
OLD_VALUE_NAMES = ('QiniuFileSync', 'ClockinSync')   # 历史启动项，迁移时删除
MODE_BY_LABEL = {v: k for k, v in PROXY_MODES.items()}
SYNC_BY_LABEL = {v: k for k, v in config.SYNC_MODES.items()}


def _project_root():
    # 本文件位于 <root>/app/gui/settings_page.py
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _launch_cmd():
    exe = sys.executable
    if exe.lower().endswith('python.exe'):
        cand = exe[:-len('python.exe')] + 'pythonw.exe'
        if os.path.exists(cand):
            exe = cand
    return '"{0}" "{1}"'.format(exe, os.path.join(_project_root(), 'run.pyw'))


def is_autostart_enabled() -> bool:
    if winreg is None:
        return False
    for name in (VALUE_NAME,) + OLD_VALUE_NAMES:
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
                winreg.QueryValueEx(k, name)
                return True
        except OSError:
            continue
    return False


def set_autostart(enable: bool):
    if winreg is None:
        raise RuntimeError('开机自启仅支持 Windows')
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
        if enable:
            winreg.SetValueEx(k, VALUE_NAME, 0, winreg.REG_SZ, _launch_cmd())
        for name in (VALUE_NAME,) + OLD_VALUE_NAMES:
            if enable and name == VALUE_NAME:
                continue
            try:
                winreg.DeleteValue(k, name)
            except OSError:
                pass


class SettingsPage(ttk.Frame):
    def __init__(self, master, app):
        super().__init__(master, padding=0)
        self.app = app
        cfg = app.cfg
        self.v_ak = tk.StringVar(value=cfg.get('ak', ''))
        self.v_sk = tk.StringVar(value=cfg.get('sk', ''))
        self.v_bucket = tk.StringVar(value=cfg.get('bucket', ''))
        self.v_region = tk.StringVar(value=cfg.get('region', 'z0'))
        self.v_domain = tk.StringVar(value=cfg.get('download_domain', ''))
        self.v_dir = tk.StringVar(value=cfg.get('download_dir', ''))
        self.v_prefix = tk.StringVar(value=cfg.get('prefix', ''))
        self.v_interval = tk.StringVar(value=str(cfg.get('interval_minutes', 30)))
        self.v_proxy = tk.StringVar(
            value=PROXY_MODES.get(cfg.get('proxy_mode', 'auto'), PROXY_MODES['auto']))
        self.v_mode = tk.StringVar(
            value=config.SYNC_MODES.get(cfg.get('sync_mode', 'download'),
                                        config.SYNC_MODES['download']))
        self.v_overwrite = tk.BooleanVar(value=bool(cfg.get('upload_overwrite', False)))
        self.v_autostart = tk.BooleanVar(value=is_autostart_enabled())
        self._build()

    # ---------- UI ----------

    def _card(self, parent, title):
        card = theme.card(parent, padding=(14, 9))
        card.pack(fill='x', pady=(0, 10))
        card.columnconfigure(1, weight=1)
        return card, theme.section(card, title)

    def _row(self, card, row, label, widget, hint=None):
        ttk.Label(card, text=label, style='Card.TLabel').grid(
            row=row, column=0, sticky='e', padx=(0, 12), pady=2)
        widget.grid(row=row, column=1, sticky='we', pady=2)
        if hint:
            ttk.Label(card, text=hint, style='Card.Muted.TLabel').grid(
                row=row, column=2, sticky='w', padx=(10, 0))
        return row + 1

    def _build(self):
        # 固定底部操作栏：内容再高，「保存 / 测试」也始终可见
        bar = theme.card(self, padding=(12, 10))
        bar.pack(side='bottom', fill='x', padx=16, pady=(0, 14))
        ttk.Label(bar, text='修改后需保存；「测试上传」会创建并自动删除一个临时对象',
                  style='Card.Muted.TLabel').pack(side='left')
        ttk.Button(bar, text='测试上传', command=self._test_upload).pack(
            side='right', padx=(6, 0))
        ttk.Button(bar, text='测试连接', command=self._test).pack(
            side='right', padx=(6, 0))
        ttk.Button(bar, text='保存设置', style='Accent.TButton',
                   command=self._save).pack(side='right', padx=(6, 0))

        # 卡片较多，用可滚动画布承载
        canvas = tk.Canvas(self, bg=theme.BG, highlightthickness=0, bd=0)
        vsb = ttk.Scrollbar(self, orient='vertical', command=canvas.yview)
        canvas.configure(yscrollcommand=vsb.set)
        vsb.pack(side='right', fill='y')
        canvas.pack(side='left', fill='both', expand=True)
        outer = ttk.Frame(canvas, padding=(16, 10))
        win = canvas.create_window((0, 0), window=outer, anchor='nw')
        outer.bind('<Configure>',
                   lambda _e: canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.bind('<Configure>', lambda e: canvas.itemconfig(win, width=e.width))

        def on_wheel(e):
            canvas.yview_scroll(int(-1 * (e.delta / 120)), 'units')
        canvas.bind('<Enter>', lambda _e: canvas.bind_all('<MouseWheel>', on_wheel))
        canvas.bind('<Leave>', lambda _e: canvas.unbind_all('<MouseWheel>'))

        # ---- 连接信息 ----
        c1, r = self._card(outer, '连接 S3 兼容存储')
        ttk.Label(c1, text=config.BACKEND_NOTE, style='Card.Muted.TLabel').grid(
            row=r, column=0, columnspan=3, sticky='w', pady=(0, 8))
        r += 1
        r = self._row(c1, r, 'Access Key (AK)', ttk.Entry(c1, textvariable=self.v_ak))
        r = self._row(c1, r, 'Secret Key (SK)',
                      ttk.Entry(c1, textvariable=self.v_sk, show='●'))
        r = self._row(c1, r, 'Bucket 名称（空间名）', ttk.Entry(c1, textvariable=self.v_bucket))
        r = self._row(c1, r, '存储区域',
                      ttk.Combobox(c1, textvariable=self.v_region,
                                   values=list(UP_HOSTS), state='normal'),
                      '可手动输入区域代码（当前仅七牛云已验证）')
        r = self._row(c1, r, '下载域名 (可选)',
                      ttk.Entry(c1, textvariable=self.v_domain), '留空自动获取')
        r = self._row(c1, r, '网络代理',
                      ttk.Combobox(c1, textvariable=self.v_proxy,
                                   values=list(PROXY_MODES.values()), state='readonly'),
                      '代理软件没开时选「直连」')

        # ---- 同步范围与方向 ----
        c2, r = self._card(outer, '同步范围与方向')

        # 方向与间隔放首行并排，保证打开设置页第一屏就能看到
        f_mode = ttk.Frame(c2, style='Card.TFrame', padding=0)
        f_mode.grid(row=r, column=0, columnspan=3, sticky='we', pady=(0, 6))
        ttk.Label(f_mode, text='同步方向', style='Card.TLabel').pack(side='left')
        ttk.Combobox(f_mode, textvariable=self.v_mode,
                     values=list(config.SYNC_MODES.values()),
                     state='readonly', width=23).pack(side='left', padx=(10, 26))
        ttk.Label(f_mode, text='间隔 (分钟)', style='Card.TLabel').pack(side='left')
        ttk.Spinbox(f_mode, textvariable=self.v_interval,
                    from_=1, to=1440, width=6).pack(side='left', padx=(10, 0))
        ttk.Label(f_mode, text='云端目录留空 = 整个桶',
                  style='Card.Muted.TLabel').pack(side='right')
        r += 1

        ttk.Label(c2, text='本地目录', style='Card.TLabel').grid(
            row=r, column=0, sticky='e', padx=(0, 12), pady=3)
        f_dir = ttk.Frame(c2, style='Card.TFrame', padding=0)
        f_dir.grid(row=r, column=1, columnspan=2, sticky='we', pady=3)
        f_dir.columnconfigure(0, weight=1)
        ttk.Entry(f_dir, textvariable=self.v_dir).grid(row=0, column=0, sticky='we')
        ttk.Button(f_dir, text='浏览…', command=self._browse).grid(row=0, column=1, padx=(6, 0))
        ttk.Button(f_dir, text='打开目录', command=self._open_dir).grid(row=0, column=2, padx=(6, 0))
        r += 1

        ttk.Label(c2, text='云端目录（S3 Key 前缀）', style='Card.TLabel').grid(
            row=r, column=0, sticky='e', padx=(0, 12), pady=3)
        f_pre = ttk.Frame(c2, style='Card.TFrame', padding=0)
        f_pre.grid(row=r, column=1, columnspan=2, sticky='we', pady=3)
        f_pre.columnconfigure(0, weight=1)
        ttk.Entry(f_pre, textvariable=self.v_prefix).grid(row=0, column=0, sticky='we')
        ttk.Button(f_pre, text='浏览远端', command=self._browse_remote).grid(
            row=0, column=1, padx=(6, 0))
        ttk.Button(f_pre, text='打开云端', command=self._open_cloud).grid(
            row=0, column=2, padx=(6, 0))
        r += 1

        f_chk = ttk.Frame(c2, style='Card.TFrame', padding=0)
        f_chk.grid(row=r, column=0, columnspan=3, sticky='w', pady=(4, 2))
        ttk.Checkbutton(f_chk, text='上传时覆盖云端同名文件（默认不覆盖）',
                        variable=self.v_overwrite,
                        style='Card.TCheckbutton').pack(side='left')
        ttk.Checkbutton(f_chk, text='开机自动启动',
                        variable=self.v_autostart, command=self._toggle_autostart,
                        style='Card.TCheckbutton').pack(side='left', padx=(20, 0))

    # ---------- 动作 ----------

    def _browse(self):
        d = filedialog.askdirectory(parent=self)
        if d:
            self.v_dir.set(os.path.normpath(d))

    def _open_dir(self):
        self.app.open_local_dir(self.v_dir.get().strip())

    def _browse_remote(self):
        self.app.focus_browser(self.v_prefix.get().strip())

    def _open_cloud(self):
        self.app.open_cloud_dir(self.v_prefix.get().strip())

    def collect(self) -> dict:
        try:
            interval = max(1, int(self.v_interval.get()))
        except ValueError:
            interval = 30
        return {
            'ak': self.v_ak.get().strip(),
            'sk': self.v_sk.get().strip(),
            'bucket': self.v_bucket.get().strip(),
            'region': self.v_region.get(),
            'download_domain': self.v_domain.get().strip(),
            'download_dir': self.v_dir.get().strip(),
            'prefix': self.v_prefix.get().strip().replace('\\', '/').lstrip('/'),
            'interval_minutes': interval,
            'proxy_mode': MODE_BY_LABEL.get(self.v_proxy.get(), 'auto'),
            'sync_mode': SYNC_BY_LABEL.get(self.v_mode.get(), 'download'),
            'upload_overwrite': bool(self.v_overwrite.get()),
        }

    def _save(self):
        data = self.collect()
        if not (data['ak'] and data['sk'] and data['bucket'] and data['download_dir']):
            messagebox.showwarning('提示', 'AK / SK / 空间名 / 本地目录均不能为空。', parent=self)
            return
        self.app.apply_settings(data)
        messagebox.showinfo('已保存', '设置已保存并生效。', parent=self)

    def _test(self):
        data = self.collect()
        if not (data['ak'] and data['sk'] and data['bucket']):
            messagebox.showwarning('提示', '请先填写 AK / SK / 空间名。', parent=self)
            return
        self.app.test_connection(data)

    def _test_upload(self):
        data = self.collect()
        if not (data['ak'] and data['sk'] and data['bucket']):
            messagebox.showwarning('提示', '请先填写 AK / SK / 空间名。', parent=self)
            return
        if not messagebox.askyesno(
                '测试上传',
                '将向空间上传一个极小的临时测试对象，成功后立即删除。\n'
                '云端目录: {0}\n\n是否继续？'.format(data['prefix'] or '桶根目录'),
                parent=self):
            return
        self.app.test_upload(data)

    def _toggle_autostart(self):
        try:
            set_autostart(self.v_autostart.get())
        except Exception as e:
            messagebox.showerror('错误', '设置开机自启失败: {0}'.format(e), parent=self)
        self._refresh_autostart_label()

    def _refresh_autostart_label(self):
        self.v_autostart.set(is_autostart_enabled())
