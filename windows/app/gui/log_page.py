# -*- coding: utf-8 -*-
"""日志页：只读滚动日志，按级别着色，自动限制内存中的行数。"""
import os
import tkinter as tk
from tkinter import ttk, messagebox

from app import config
from app.gui import theme

MAX_LINES = 1500


class LogPage(ttk.Frame):
    def __init__(self, master, app):
        super().__init__(master, padding=0)
        self.app = app

        outer = ttk.Frame(self, padding=(16, 14))
        outer.pack(fill='both', expand=True)

        toolbar = ttk.Frame(outer, style='Card.TFrame', padding=(12, 9))
        toolbar.pack(fill='x', pady=(0, 10))
        ttk.Label(toolbar, text='运行日志', style='Section.TLabel').pack(side='left')
        ttk.Label(toolbar, text='同步 / 上传 / 下载 / 删除 / 错误记录',
                  style='Card.Muted.TLabel').pack(side='left', padx=12)
        ttk.Button(toolbar, text='打开日志文件',
                   command=self._open_file).pack(side='right', padx=(6, 0))
        ttk.Button(toolbar, text='清空', command=self.clear).pack(side='right')

        card = theme.card(outer, padding=1)
        card.pack(fill='both', expand=True)
        card.columnconfigure(0, weight=1)
        card.rowconfigure(0, weight=1)

        self.text = tk.Text(card, height=16, state='disabled', font=theme.MONO,
                            wrap='none', bg=theme.CARD, fg=theme.TEXT,
                            relief='flat', highlightthickness=0, bd=0,
                            padx=12, pady=10, spacing1=2,
                            selectbackground=theme.PRIMARY_SOFT)
        vsb = ttk.Scrollbar(card, orient='vertical', command=self.text.yview)
        hsb = ttk.Scrollbar(card, orient='horizontal', command=self.text.xview)
        self.text.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        self.text.grid(row=0, column=0, sticky='nsew')
        vsb.grid(row=0, column=1, sticky='ns')
        hsb.grid(row=1, column=0, sticky='we')

        self.text.tag_configure('INFO', foreground=theme.TEXT)
        self.text.tag_configure('WARNING', foreground=theme.WARN)
        self.text.tag_configure('ERROR', foreground=theme.DANGER)
        self.text.tag_configure('DEBUG', foreground=theme.MUTED)

    def append(self, msg: str):
        level = 'INFO'
        for lv in ('ERROR', 'WARNING', 'DEBUG'):
            if ' ' + lv + ' ' in msg:
                level = lv
                break
        self.text.configure(state='normal')
        self.text.insert('end', msg.rstrip('\n') + '\n', level)
        try:
            lines = int(self.text.index('end-1c').split('.')[0])
            if lines > MAX_LINES:
                self.text.delete('1.0', '{0}.0'.format(lines - MAX_LINES + 200))
        except Exception:
            pass
        self.text.see('end')
        self.text.configure(state='disabled')

    def clear(self):
        self.text.configure(state='normal')
        self.text.delete('1.0', 'end')
        self.text.configure(state='disabled')

    def _open_file(self):
        path = os.path.join(config.APP_DIR, 'app.log')
        if os.path.isfile(path):
            os.startfile(path)
        else:
            messagebox.showinfo('提示', '日志文件尚未生成：{0}'.format(path), parent=self)
