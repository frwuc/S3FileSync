# -*- coding: utf-8 -*-
"""统一浅色主题：配色 / 字体 / ttk 控件样式（仅用标准库，无新增依赖）。"""
import tkinter as tk
from tkinter import ttk

# ---------- 配色 ----------
BG = '#F4F6FA'            # 窗口底色
CARD = '#FFFFFF'          # 卡片
BORDER = '#E3E8F0'        # 描边 / 分隔线
TEXT = '#1F2937'          # 主文字
MUTED = '#6B7280'         # 次要文字
PRIMARY = '#2563EB'       # 主色
PRIMARY_DARK = '#1D4ED8'
PRIMARY_SOFT = '#E8F0FE'  # 选中 / 悬停
DANGER = '#DC2626'
DANGER_DARK = '#B91C1C'
SUCCESS = '#059669'
WARN = '#D97706'
ROW_ALT = '#F8FAFC'
HEAD_BG = '#EEF2F8'
WHITE = '#FFFFFF'

# ---------- 字体 ----------
FONT_NAME = 'Microsoft YaHei UI'
FONT = (FONT_NAME, 10)
FONT_SM = (FONT_NAME, 9)
FONT_BOLD = (FONT_NAME, 10, 'bold')
FONT_TITLE = (FONT_NAME, 14, 'bold')
MONO = ('Consolas', 9)


def setup(root):
    """应用主题，返回 ttk.Style。"""
    root.configure(bg=BG)
    s = ttk.Style(root)
    try:
        s.theme_use('clam')
    except tk.TclError:
        pass

    s.configure('.', background=BG, foreground=TEXT, font=FONT, borderwidth=0)

    # 容器
    s.configure('TFrame', background=BG)
    s.configure('Card.TFrame', background=CARD, bordercolor=BORDER,
                borderwidth=1, relief='solid')
    s.configure('Header.TFrame', background=CARD, borderwidth=0)
    s.configure('Toolbar.TFrame', background=HEAD_BG, bordercolor=BORDER,
                borderwidth=1, relief='solid')
    s.configure('Status.TFrame', background=CARD, borderwidth=0)

    # 标签
    s.configure('TLabel', background=BG, foreground=TEXT, font=FONT)
    s.configure('Card.TLabel', background=CARD, foreground=TEXT, font=FONT)
    s.configure('Muted.TLabel', background=BG, foreground=MUTED, font=FONT_SM)
    s.configure('Card.Muted.TLabel', background=CARD, foreground=MUTED, font=FONT_SM)
    s.configure('Toolbar.TLabel', background=HEAD_BG, foreground=TEXT, font=FONT)
    s.configure('Title.TLabel', background=CARD, foreground=TEXT, font=FONT_TITLE)
    s.configure('Section.TLabel', background=CARD, foreground=PRIMARY, font=FONT_BOLD)
    s.configure('Status.TLabel', background=CARD, foreground=MUTED, font=FONT_SM)
    s.configure('Card.Error.TLabel', background=CARD, foreground=DANGER, font=FONT_SM)
    s.configure('Card.Ok.TLabel', background=CARD, foreground=SUCCESS, font=FONT_SM)
    s.configure('Pill.TLabel', background='#E7F6EF', foreground=SUCCESS,
                font=FONT_BOLD, padding=(10, 4))
    s.configure('PillWarn.TLabel', background='#FEF3C7', foreground=WARN,
                font=FONT_BOLD, padding=(10, 4))
    s.configure('PillMode.TLabel', background=PRIMARY_SOFT, foreground=PRIMARY_DARK,
                font=FONT_BOLD, padding=(10, 4))
    s.configure('TSeparator', background=BORDER)

    # 按钮
    s.configure('TButton', background=CARD, foreground=TEXT, bordercolor=BORDER,
                lightcolor=CARD, darkcolor=CARD, relief='flat', padding=(14, 7), font=FONT)
    s.map('TButton',
          background=[('pressed', PRIMARY_SOFT), ('active', PRIMARY_SOFT)],
          bordercolor=[('active', PRIMARY)],
          foreground=[('active', PRIMARY)])
    s.configure('Accent.TButton', background=PRIMARY, foreground=WHITE,
                bordercolor=PRIMARY, lightcolor=PRIMARY, darkcolor=PRIMARY,
                relief='flat', padding=(16, 7), font=FONT_BOLD)
    s.map('Accent.TButton',
          background=[('pressed', PRIMARY_DARK), ('active', PRIMARY_DARK)],
          bordercolor=[('active', PRIMARY_DARK)],
          foreground=[('active', WHITE), ('pressed', WHITE)])
    s.configure('Danger.TButton', background=CARD, foreground=DANGER,
                bordercolor='#F3C7C7', lightcolor=CARD, darkcolor=CARD,
                relief='flat', padding=(14, 7), font=FONT)
    s.map('Danger.TButton',
          background=[('pressed', '#FEE2E2'), ('active', '#FEE2E2')],
          bordercolor=[('active', DANGER)],
          foreground=[('active', DANGER_DARK)])
    s.configure('Ghost.TButton', background=CARD, foreground=MUTED,
                bordercolor=CARD, lightcolor=CARD, darkcolor=CARD,
                relief='flat', padding=(10, 6), font=FONT_SM)
    s.map('Ghost.TButton', background=[('active', PRIMARY_SOFT)],
          foreground=[('active', PRIMARY)])

    # 输入控件
    for name in ('TEntry', 'TSpinbox'):
        s.configure(name, fieldbackground=CARD, foreground=TEXT,
                    bordercolor=BORDER, lightcolor=BORDER, darkcolor=BORDER,
                    insertcolor=TEXT, relief='flat', padding=6)
        s.map(name, bordercolor=[('focus', PRIMARY)],
              lightcolor=[('focus', PRIMARY)], darkcolor=[('focus', PRIMARY)])
    s.configure('TCombobox', fieldbackground=CARD, background=CARD,
                foreground=TEXT, arrowcolor=MUTED, bordercolor=BORDER,
                lightcolor=BORDER, darkcolor=BORDER, relief='flat', padding=5)
    s.map('TCombobox', bordercolor=[('focus', PRIMARY)],
          fieldbackground=[('readonly', CARD)])
    s.configure('TCheckbutton', background=CARD, foreground=TEXT, font=FONT)
    s.configure('Card.TCheckbutton', background=CARD, foreground=TEXT, font=FONT)
    s.map('Card.TCheckbutton', background=[('active', CARD)])

    # 标签页
    s.configure('TNotebook', background=BG, bordercolor=BORDER,
                borderwidth=0, tabmargins=(0, 0, 0, 0))
    s.configure('TNotebook.Tab', background=BG, foreground=MUTED,
                bordercolor=BORDER, padding=(22, 10), font=FONT_BOLD)
    s.map('TNotebook.Tab',
          background=[('selected', CARD), ('active', HEAD_BG)],
          foreground=[('selected', PRIMARY), ('active', TEXT)])

    # 表格
    s.configure('Treeview', background=CARD, fieldbackground=CARD,
                foreground=TEXT, bordercolor=BORDER, lightcolor=CARD,
                darkcolor=CARD, rowheight=28, font=FONT)
    s.configure('Treeview.Heading', background=HEAD_BG, foreground=MUTED,
                font=FONT_BOLD, relief='flat', bordercolor=BORDER, padding=(8, 8))
    s.map('Treeview.Heading', background=[('active', PRIMARY_SOFT)],
          foreground=[('active', PRIMARY)])
    s.map('Treeview', background=[('selected', PRIMARY_SOFT)],
          foreground=[('selected', TEXT)])

    # 滚动条
    s.configure('TScrollbar', background='#CBD5E1', troughcolor=BG,
                bordercolor=BG, arrowcolor=MUTED, relief='flat', width=10)
    s.map('TScrollbar', background=[('active', MUTED)])
    return s


def card(parent, **kw):
    """带浅色描边的白色卡片容器。"""
    pad = kw.pop('padding', (16, 14))
    f = ttk.Frame(parent, style='Card.TFrame', padding=pad, **kw)
    return f


def section(parent, text, row=0):
    """卡片内的小标题 + 分隔线，返回下一个可用行号。"""
    ttk.Label(parent, text=text, style='Section.TLabel').grid(
        row=row, column=0, columnspan=3, sticky='w', pady=(0, 2))
    sep = ttk.Separator(parent, orient='horizontal')
    sep.grid(row=row + 1, column=0, columnspan=3, sticky='we', pady=(0, 8))
    return row + 2


def logo(parent, size=36):
    """用 Canvas 画一个蓝色圆形徽标（云 + 下载箭头），返回 Canvas。"""
    c = tk.Canvas(parent, width=size, height=size, bg=CARD,
                  highlightthickness=0, bd=0)
    c.create_oval(1, 1, size - 1, size - 1, fill=PRIMARY, outline=PRIMARY)
    u = size / 36.0
    c.create_oval(8 * u, 12 * u, 19 * u, 23 * u, fill=WHITE, outline=WHITE)
    c.create_oval(15 * u, 8 * u, 27 * u, 21 * u, fill=WHITE, outline=WHITE)
    c.create_rectangle(11 * u, 17 * u, 25 * u, 23 * u, fill=WHITE, outline=WHITE)
    # 一上一下两个箭头：上传 + 下载 / 双向
    c.create_line(14 * u, 19 * u, 14 * u, 29 * u, fill=PRIMARY,
                  width=max(2, int(2 * u)), arrow='last',
                  arrowshape=(5 * u, 6 * u, 2.5 * u))
    c.create_line(23 * u, 29 * u, 23 * u, 19 * u, fill=PRIMARY,
                  width=max(2, int(2 * u)), arrow='last',
                  arrowshape=(5 * u, 6 * u, 2.5 * u))
    return c
