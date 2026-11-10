# -*- coding: utf-8 -*-
"""主窗口装配：顶部品牌栏 + Notebook（设置/文件浏览/日志）+ 状态栏。"""
import tkinter as tk
from tkinter import ttk

from app import config
from app.gui import theme


def build(app):
    root = app.root
    theme.setup(root)
    root.title(config.APP_NAME)
    sw, sh = root.winfo_screenwidth(), root.winfo_screenheight()
    w = min(1000, max(860, sw - 160))
    h = min(820, max(620, sh - 120))
    root.geometry('{0}x{1}'.format(w, h))
    root.minsize(860, 560)
    root.protocol('WM_DELETE_WINDOW', app.hide_window)

    # ---------- 顶部品牌栏 ----------
    header = ttk.Frame(root, style='Header.TFrame', padding=(18, 12))
    header.pack(fill='x', side='top')
    theme.logo(header, 36).pack(side='left', padx=(0, 12))

    titles = ttk.Frame(header, style='Header.TFrame')
    titles.pack(side='left')
    ttk.Label(titles, text=config.APP_NAME, style='Title.TLabel').pack(anchor='w')
    ttk.Label(titles, text='S3 兼容对象存储 · 本地 ⇄ 云端 · 上传 / 下载 / 双向 · 任意文件',
              style='Card.Muted.TLabel').pack(anchor='w', pady=(2, 0))

    right = ttk.Frame(header, style='Header.TFrame')
    right.pack(side='right')
    app.badge_label = ttk.Label(right, text='● 自动同步运行中', style='Pill.TLabel')
    app.badge_label.pack(side='left', padx=(0, 8))
    app.mode_label = ttk.Label(right, text='仅下载', style='PillMode.TLabel')
    app.mode_label.pack(side='left', padx=(0, 12))
    ttk.Button(right, text='立即同步', style='Accent.TButton',
               command=app.start_sync).pack(side='left', padx=3)

    # 临时单跑其它方向，不改默认设置
    run_menu = tk.Menu(root, tearoff=0, bg=theme.CARD, fg=theme.TEXT,
                       activebackground=theme.PRIMARY_SOFT,
                       activeforeground=theme.TEXT, relief='flat', bd=0)
    run_menu.add_command(label='仅上传一轮（本地 → 云端）',
                         command=lambda: app.start_sync('upload'))
    run_menu.add_command(label='仅下载一轮（云端 → 本地）',
                         command=lambda: app.start_sync('download'))
    run_menu.add_command(label='双向同步一轮',
                         command=lambda: app.start_sync('both'))
    run_menu.add_separator()
    run_menu.add_command(label='打开本地目录', command=app.open_download_dir)
    run_menu.add_command(label='打开云端目录（七牛云）', command=app.open_cloud_dir)
    run_menu.add_separator()
    run_menu.add_command(label='隐藏窗口到托盘', command=app.hide_window)
    btn_run = ttk.Button(right, text='单跑 ▾')
    def _pop_run_menu():
        try:
            run_menu.tk_popup(btn_run.winfo_rootx(),
                              btn_run.winfo_rooty() + btn_run.winfo_height())
        finally:
            run_menu.grab_release()
    btn_run.configure(command=_pop_run_menu)
    btn_run.pack(side='left', padx=(3, 0))

    tk.Frame(root, bg=theme.BORDER, height=1).pack(fill='x', side='top')

    # ---------- 标签页 ----------
    from app.gui.settings_page import SettingsPage
    from app.gui.browser_page import BrowserPage
    from app.gui.log_page import LogPage

    nb = app.notebook
    nb.pack(fill='both', expand=True, padx=16, pady=(14, 8))

    settings = SettingsPage(nb, app)
    nb.add(settings, text='  设置  ')
    browser = BrowserPage(nb, app)
    nb.add(browser, text='  文件浏览  ')
    logp = LogPage(nb, app)
    nb.add(logp, text='  日志  ')

    app.pages = {'settings': settings, 'browser': browser, 'log': logp}

    def on_tab_changed(_event):
        try:
            if nb.index('current') == 1:      # 首次进入文件浏览页自动加载
                browser.auto_refresh()
        except tk.TclError:
            pass
    nb.bind('<<NotebookTabChanged>>', on_tab_changed)

    # ---------- 状态栏 ----------
    bar = ttk.Frame(root, style='Status.TFrame', padding=(16, 7))
    bar.pack(fill='x', side='bottom')
    tk.Label(bar, text='●', bg=theme.CARD, fg=theme.SUCCESS,
             font=(theme.FONT_NAME, 9)).pack(side='left')
    ttk.Label(bar, textvariable=app.status_var,
              style='Status.TLabel').pack(side='left', padx=(6, 0))
    ttk.Label(bar, text='关闭窗口 = 隐藏到托盘 · 退出请使用托盘菜单',
              style='Status.TLabel').pack(side='right')
    tk.Frame(root, bg=theme.BORDER, height=1).pack(fill='x', side='bottom')
