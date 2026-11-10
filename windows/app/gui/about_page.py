# -*- coding: utf-8 -*-
"""关于页：项目信息、版本、后端说明与联系方式。

说明：当前后端为七牛云 Kodo（S3 兼容）；其余 S3 兼容服务商尚未验证。
"""
import webbrowser
import tkinter as tk
from tkinter import ttk, messagebox

from app import config
from app.gui import theme

REPO_URL = 'https://gitee.com/frwuc/S3FileSync'
AUTHOR_EMAIL = 'frwucc@163.com'


class AboutPage(ttk.Frame):
    def __init__(self, master, app):
        super().__init__(master, padding=0)
        self.app = app

        outer = ttk.Frame(self, padding=(24, 22))
        outer.pack(fill='both', expand=True)

        # ---------- 头部：徽标 + 名称 + 版本 ----------
        head = ttk.Frame(outer, style='Card.TFrame', padding=(20, 18))
        head.pack(fill='x', pady=(0, 16))
        theme.logo(head, 56).pack(side='left', padx=(0, 16))
        info = ttk.Frame(head, style='Card.TFrame')
        info.pack(side='left', fill='x', expand=True)
        ttk.Label(info, text=config.APP_NAME, style='Title.TLabel').pack(anchor='w')
        ttk.Label(info, text='S3 兼容对象存储 · 本地 ⇄ 云端同步工具',
                  style='Card.Muted.TLabel').pack(anchor='w', pady=(2, 0))
        ttk.Label(info, text='版本 {0}'.format(config.APP_VERSION),
                  style='Card.Muted.TLabel').pack(anchor='w', pady=(2, 0))

        # ---------- 项目简介 ----------
        c1 = theme.card(outer, padding=(18, 14))
        c1.pack(fill='x', pady=(0, 12))
        ttk.Label(c1, text='项目简介', style='Section.TLabel').pack(anchor='w')
        desc = ('将本地目录与 S3 兼容对象存储（云端）之间的文件自动同步。\n'
                '支持三种方向：仅下载（云端 → 本地）、仅上传（本地 → 云端）、'
                '双向同步；不限文件类型，可同步任意文件。\n'
                '内置托盘常驻、定时自动同步、手动单文件 / 单文件夹上传下载、'
                '远端目录浏览与删除。')
        ttk.Label(c1, text=desc, style='Card.TLabel', wraplength=720,
                  justify='left').pack(anchor='w', pady=(6, 0))

        # ---------- 后端说明 ----------
        c2 = theme.card(outer, padding=(18, 14))
        c2.pack(fill='x', pady=(0, 12))
        ttk.Label(c2, text='后端说明', style='Section.TLabel').pack(anchor='w')
        ttk.Label(c2, text=config.BACKEND_NOTE, style='Card.TLabel',
                  wraplength=720, justify='left').pack(anchor='w', pady=(6, 0))

        # ---------- 项目信息 ----------
        c3 = theme.card(outer, padding=(18, 14))
        c3.pack(fill='x', pady=(0, 12))
        ttk.Label(c3, text='项目信息', style='Section.TLabel').pack(anchor='w', pady=(0, 6))
        self._kv(c3, '开源仓库', REPO_URL, url=REPO_URL)
        self._kv_email(c3, '联系邮箱', AUTHOR_EMAIL)
        self._kv(c3, '配置 / 数据目录', config.APP_DIR)

    # ---------- 信息行 ----------

    def _kv(self, parent, k, v, url=None):
        f = ttk.Frame(parent, style='Card.TFrame')
        f.pack(fill='x', pady=(3, 3))
        ttk.Label(f, text=k, style='Card.TLabel', width=14).pack(side='left')
        if url:
            lbl = ttk.Label(f, text=v, style='Card.TLabel',
                            foreground=theme.PRIMARY, cursor='hand2')
            lbl.pack(side='left')
            lbl.bind('<Button-1>', lambda _e: webbrowser.open(url))
        else:
            ttk.Label(f, text=v, style='Card.Muted.TLabel').pack(side='left')

    def _kv_email(self, parent, k, mail):
        f = ttk.Frame(parent, style='Card.TFrame')
        f.pack(fill='x', pady=(3, 3))
        ttk.Label(f, text=k, style='Card.TLabel', width=14).pack(side='left')
        lbl = ttk.Label(f, text=mail, style='Card.TLabel',
                        foreground=theme.PRIMARY, cursor='hand2')
        lbl.pack(side='left')
        lbl.bind('<Button-1>', lambda _e: self._copy(mail))
        ttk.Button(f, text='复制', style='Ghost.TButton',
                   command=lambda: self._copy(mail)).pack(side='left', padx=(8, 0))

    def _copy(self, text):
        try:
            self.clipboard_clear()
            self.clipboard_append(text)
            messagebox.showinfo('已复制', '已复制到剪贴板：\n{0}'.format(text), parent=self)
        except Exception as e:
            messagebox.showerror('复制失败', str(e), parent=self)
