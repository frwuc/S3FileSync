# -*- coding: utf-8 -*-
"""文件浏览页：任意云端目录列举、分页、排序、上传/下载/删除、右键菜单。

云端目录输入框可以填任意 Key 前缀，留空即浏览整个空间，不受设置页
「同步范围」限制；双击以 / 结尾的目录占位对象可进入该目录。
所有网络操作通过 app.browser_list / browser_upload / browser_download /
browser_delete 提交到后台单线程执行器，结果经事件队列回到主线程刷新 UI。
注意：Treeview 的行 id 是字符串（I001…），必须用 id→对象 的映射取选中项。
"""
import datetime
import os
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

from app.gui import theme


def _human_size(n):
    n = float(n or 0)
    for unit in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or unit == 'GB':
            return ('{0:.0f} {1}' if unit in ('B', 'KB') else '{0:.1f} {1}').format(n, unit)
        n /= 1024


def _fmt_put_time(put_time):
    if not put_time:
        return '-'
    try:
        # 七牛 putTime 为 100ns 单位
        return datetime.datetime.fromtimestamp(float(put_time) / 1e7).strftime('%Y-%m-%d %H:%M')
    except Exception:
        return str(put_time)


class BrowserPage(ttk.Frame):
    def __init__(self, master, app):
        super().__init__(master, padding=0)
        self.app = app
        self.items = []
        self.row_map = {}          # Treeview 行 id -> 对象 dict
        self.next_marker = None    # 下一页游标
        self.page_start = None     # 当前页起始游标
        self.marker_stack = []     # 上一页游标栈
        self.busy = False
        self.loaded = False

        self.v_prefix = tk.StringVar(value=app.cfg.get('prefix', ''))
        self._build()

    # ---------- UI ----------

    def _build(self):
        outer = ttk.Frame(self, padding=(16, 14))
        outer.pack(fill='both', expand=True)

        # 工具栏：第一行目录导航，第二行分页（避免一行塞满导致右侧被裁）
        toolbar = ttk.Frame(outer, style='Toolbar.TFrame', padding=(12, 9))
        toolbar.pack(fill='x', pady=(0, 10))
        row1 = ttk.Frame(toolbar, style='Toolbar.TFrame', padding=0)
        row1.pack(fill='x')
        row2 = ttk.Frame(toolbar, style='Toolbar.TFrame', padding=0)
        row2.pack(fill='x', pady=(8, 0))

        ttk.Label(row1, text='云端目录（S3 Key 前缀）', style='Toolbar.TLabel').pack(side='left')
        ent = ttk.Entry(row1, textvariable=self.v_prefix, width=32)
        ent.pack(side='left', padx=(8, 10))
        ent.bind('<Return>', lambda _e: self.refresh())
        self.btn_refresh = ttk.Button(row1, text='刷新', style='Accent.TButton',
                                      command=self.refresh)
        self.btn_refresh.pack(side='left', padx=(0, 6))
        ttk.Button(row1, text='全部文件', style='Ghost.TButton',
                   command=self.show_all).pack(side='left', padx=(0, 6))
        ttk.Button(row1, text='上级', style='Ghost.TButton',
                   command=self.up_level).pack(side='left', padx=(0, 6))
        ttk.Button(row1, text='打开云端', style='Ghost.TButton',
                   command=self._open_cloud).pack(side='left')

        self.btn_prev = ttk.Button(row2, text='上一页', command=self.prev_page)
        self.btn_prev.pack(side='left', padx=(0, 6))
        self.btn_next = ttk.Button(row2, text='下一页', command=self.next_page)
        self.btn_next.pack(side='left')
        self.lbl_page = ttk.Label(row2, text='第 1 页', style='Toolbar.TLabel')
        self.lbl_page.pack(side='left', padx=(14, 0))
        ttk.Label(row2, text='可填任意 S3 Key 前缀；留空浏览整个空间',
                  style='Toolbar.TLabel').pack(side='right')

        # 列表
        card = theme.card(outer, padding=1)
        card.pack(fill='both', expand=True)
        card.columnconfigure(0, weight=1)
        card.rowconfigure(0, weight=1)

        cols = (
            ('no', '序号', 50, 'center'),
            ('key', '对象 Key', 370, 'w'),
            ('size', '大小', 82, 'e'),
            ('mtime', '上传时间', 150, 'w'),
        )
        self.tree = ttk.Treeview(card, columns=[c[0] for c in cols],
                                 show='headings', selectmode='extended')
        for col, title, width, anchor in cols:
            head_kw = {'text': title}
            if col != 'no':
                head_kw['command'] = lambda c=col: self.sort_by(c)
            self.tree.heading(col, **head_kw)
            self.tree.column(col, width=width, anchor=anchor,
                             stretch=(col == 'key'), minwidth=width)
        self.tree.tag_configure('odd', background=theme.ROW_ALT)

        vsb = ttk.Scrollbar(card, orient='vertical', command=self.tree.yview)
        hsb = ttk.Scrollbar(card, orient='horizontal', command=self.tree.xview)
        self.tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        self.tree.grid(row=0, column=0, sticky='nsew')
        vsb.grid(row=0, column=1, sticky='ns')
        hsb.grid(row=1, column=0, sticky='we')

        self.tree.bind('<<TreeviewSelect>>', self._on_select)
        self.tree.bind('<Double-1>', self._on_double_click)
        self.tree.bind('<Button-3>', self._on_right_click)
        self.menu = tk.Menu(self, tearoff=0, bg=theme.CARD, fg=theme.TEXT,
                            activebackground=theme.PRIMARY_SOFT,
                            activeforeground=theme.TEXT, relief='flat', bd=0)
        self.menu.add_command(label='下载所选', command=self.download_selected)
        self.menu.add_command(label='删除所选', command=self.delete_selected)
        self.menu.add_separator()
        self.menu.add_command(label='进入该目录', command=self._enter_dir)
        self.menu.add_command(label='复制对象 Key', command=self._copy_key)

        # 底部：状态行独占一行（不会被按钮挤掉），按钮行单独一行
        bottom = ttk.Frame(outer, style='Card.TFrame', padding=(12, 8))
        bottom.pack(fill='x', pady=(10, 0))
        status_row = ttk.Frame(bottom, style='Card.TFrame', padding=0)
        status_row.pack(fill='x', pady=(0, 8))
        self.lbl_status = ttk.Label(status_row, text='可输入任意云端目录（S3 前缀）；留空 = 整个空间',
                                    style='Card.Muted.TLabel')
        self.lbl_status.pack(side='left')
        self.lbl_sel = ttk.Label(status_row, text='已选 0 项', style='Card.Muted.TLabel')
        self.lbl_sel.pack(side='right')

        btn_row = ttk.Frame(bottom, style='Card.TFrame', padding=0)
        btn_row.pack(fill='x')
        self.btn_upload_file = ttk.Button(btn_row, text='上传文件…',
                                          command=self.upload_files)
        self.btn_upload_file.pack(side='left', padx=(0, 6))
        self.btn_upload_dir = ttk.Button(btn_row, text='上传文件夹…',
                                         command=self.upload_folder)
        self.btn_upload_dir.pack(side='left', padx=(0, 6))
        ttk.Separator(btn_row, orient='vertical').pack(side='left', fill='y', padx=4)
        self.btn_download = ttk.Button(btn_row, text='下载选中', style='Accent.TButton',
                                       command=self.download_selected)
        self.btn_download.pack(side='left', padx=(0, 6))
        self.btn_delete = ttk.Button(btn_row, text='删除选中', style='Danger.TButton',
                                     command=self.delete_selected)
        self.btn_delete.pack(side='left', padx=(0, 6))
        ttk.Button(btn_row, text='全选', style='Ghost.TButton',
                   command=self._select_all).pack(side='left')
        self._set_buttons()

    # ---------- 前缀 / 分页 / 加载 ----------

    def _dir_prefix(self):
        """把输入框当作「目录」时的前缀（补尾斜杠），用于上传目标。"""
        p = self.v_prefix.get().strip().replace('\\', '/').lstrip('/')
        if p and not p.endswith('/'):
            p += '/'
        return p

    def show_all(self):
        self.v_prefix.set('')
        self.refresh()

    def up_level(self):
        p = self.v_prefix.get().strip().replace('\\', '/').rstrip('/')
        self.v_prefix.set((p.rsplit('/', 1)[0] + '/') if '/' in p else '')
        self.refresh()

    def _open_cloud(self):
        self.app.open_cloud_dir(self.v_prefix.get().strip())

    def _enter_dir(self, key=None):
        if key is None:
            sel = self._selected_items()
            key = sel[0].get('key', '') if sel else ''
        if key and key.endswith('/'):
            self.v_prefix.set(key)
            self.refresh()

    def auto_refresh(self):
        """首次进入本页时自动加载一次。"""
        if not self.loaded and not self.busy:
            self.refresh()

    def refresh(self):
        if self.busy:
            return
        self.marker_stack = []
        self.page_start = None
        self.next_marker = None
        self._start_load('正在加载对象列表…')
        self.app.browser_list(self.v_prefix.get().strip(), None)

    def next_page(self):
        if self.busy or not self.next_marker:
            return
        self.marker_stack.append(self.page_start)
        self._start_load('正在加载下一页…')
        self.app.browser_list(self.v_prefix.get().strip(), self.next_marker)

    def prev_page(self):
        if self.busy or not self.marker_stack:
            return
        m = self.marker_stack.pop()
        self._start_load('正在加载上一页…')
        self.app.browser_list(self.v_prefix.get().strip(), m)

    def _start_load(self, text):
        self.busy = True
        self.lbl_status.configure(text=text, style='Card.TLabel')
        self._set_buttons()

    def _start_op(self, text):
        self.busy = True
        self.lbl_status.configure(text=text, style='Card.TLabel')
        self._set_buttons()

    # ---------- 数据回调（主线程） ----------

    def fill(self, items, next_marker, req_marker):
        self.items = list(items)
        self.next_marker = next_marker
        self.page_start = req_marker
        self.busy = False
        self.loaded = True
        self._render()
        self._set_buttons()
        more = '还有更多' if next_marker else '已到末页'
        self.lbl_status.configure(
            text='本页 {0} 条 · {1}{2}'.format(
                len(items), more, ' · 双击可下载/进入目录' if items else ''),
            style='Card.Ok.TLabel')

    def show_error(self, msg):
        self.busy = False
        self._set_buttons()
        short = str(msg).replace('\n', ' ')
        if len(short) > 110:
            short = short[:110] + '…'
        self.lbl_status.configure(text='加载失败: {0}（点「刷新」重试）'.format(short),
                                  style='Card.Error.TLabel')
        try:
            self.app.status_var.set('文件浏览加载失败，详见日志页')
        except Exception:
            pass

    def finish_op(self, text):
        """上传/下载/删除完成后解除忙碌状态并显示结果。"""
        self.busy = False
        self._set_buttons()
        self.lbl_status.configure(text=text, style='Card.Ok.TLabel')

    def _render(self):
        self.tree.delete(*self.tree.get_children())
        self.row_map = {}
        for i, it in enumerate(self.items):
            key = it.get('key', '')
            size_txt = '目录' if key.endswith('/') else _human_size(it.get('fsize', 0))
            iid = self.tree.insert('', 'end', values=(
                i + 1, key, size_txt, _fmt_put_time(it.get('putTime', 0)),
            ), tags=('odd',) if i % 2 else ())
            self.row_map[iid] = it
        self.lbl_page.configure(text='第 {0} 页'.format(len(self.marker_stack) + 1))
        self.lbl_sel.configure(text='已选 0 项')
        if self.items:
            self.tree.yview_moveto(0)

    def _set_buttons(self):
        has_sel = bool(self.tree.selection()) if hasattr(self, 'tree') else False
        def st(enabled):
            return 'normal' if enabled else 'disabled'
        self.btn_refresh.configure(state=st(not self.busy))
        self.btn_prev.configure(state=st(not self.busy and bool(self.marker_stack)))
        self.btn_next.configure(state=st(not self.busy and bool(self.next_marker)))
        self.btn_upload_file.configure(state=st(not self.busy))
        self.btn_upload_dir.configure(state=st(not self.busy))
        self.btn_download.configure(state=st(not self.busy and has_sel))
        self.btn_delete.configure(state=st(not self.busy and has_sel))

    def _on_select(self, _event):
        n = len(self.tree.selection())
        self.lbl_sel.configure(text='已选 {0} 项'.format(n))
        self._set_buttons()

    def _on_double_click(self, event):
        iid = self.tree.identify_row(event.y)
        if not iid:
            return
        it = self.row_map.get(iid) or {}
        self.tree.selection_set(iid)
        if it.get('key', '').endswith('/'):
            self._enter_dir(it['key'])
        else:
            self.download_selected()

    def _on_right_click(self, event):
        iid = self.tree.identify_row(event.y)
        if iid and iid not in self.tree.selection():
            self.tree.selection_set(iid)
        try:
            self.menu.tk_popup(event.x_root, event.y_root)
        finally:
            self.menu.grab_release()

    def _select_all(self):
        self.tree.selection_set(*self.tree.get_children())

    def _copy_key(self):
        keys = [it.get('key', '') for it in self._selected_items()]
        if not keys:
            return
        self.clipboard_clear()
        self.clipboard_append('\n'.join(keys))
        self.lbl_status.configure(text='已复制 {0} 个对象 Key 到剪贴板'.format(len(keys)),
                                  style='Card.Ok.TLabel')

    def sort_by(self, col):
        if not self.items:
            return
        key_fn = {
            'key': lambda x: x.get('key', ''),
            'size': lambda x: float(x.get('fsize') or 0),
            'mtime': lambda x: float(x.get('putTime') or 0),
        }.get(col)
        if key_fn is None:
            return
        reverse = not getattr(self, '_sort_reverse', False) if getattr(self, '_sort_col', None) == col else False
        self.items.sort(key=key_fn, reverse=reverse)
        self._sort_col, self._sort_reverse = col, reverse
        self._render()
        self._set_buttons()

    # ---------- 操作 ----------

    def _selected_items(self):
        return [self.row_map[i] for i in self.tree.selection() if i in self.row_map]

    def upload_files(self):
        paths = filedialog.askopenfilenames(parent=self, title='选择要上传的文件')
        if not paths:
            return
        prefix = self._dir_prefix()
        specs = [(p, prefix + os.path.basename(p).replace('\\', '/')) for p in paths]
        self._confirm_upload(specs, prefix)

    def upload_folder(self):
        d = filedialog.askdirectory(parent=self, title='选择要上传的文件夹')
        if not d:
            return
        prefix = self._dir_prefix()
        specs = []
        for root, _dirs, files in os.walk(d):
            for name in files:
                p = os.path.join(root, name)
                rel = os.path.relpath(p, d).replace(os.sep, '/')
                specs.append((p, prefix + rel))
        if not specs:
            messagebox.showinfo('提示', '所选文件夹内没有文件。', parent=self)
            return
        self._confirm_upload(specs, prefix)

    def _confirm_upload(self, specs, prefix):
        preview = '\n'.join(k for _p, k in specs[:8]) + ('\n…' if len(specs) > 8 else '')
        if not messagebox.askyesno(
                '确认上传',
                '将上传 {0} 个文件到云端目录（S3 前缀）「{1}」:\n{2}\n\n'
                '同名对象会被覆盖，是否继续？'.format(
                    len(specs), prefix or '桶根目录', preview),
                parent=self):
            return
        self._start_op('正在上传 {0} 个文件…'.format(len(specs)))
        self.app.browser_upload(specs, prefix)

    def download_selected(self):
        sel = self._selected_items()
        if not sel:
            messagebox.showinfo('提示', '请先在列表中选择对象。', parent=self)
            return
        entries = [(it.get('key'), it.get('fsize', 0)) for it in sel
                   if not it.get('key', '').endswith('/')]
        if not entries:
            messagebox.showinfo('提示', '目录占位对象不能下载，请进入目录或选择文件。', parent=self)
            return
        self._start_op('正在下载 {0} 个对象…'.format(len(entries)))
        self.app.browser_download(entries)

    def delete_selected(self):
        sel = self._selected_items()
        if not sel:
            messagebox.showinfo('提示', '请先在列表中选择对象。', parent=self)
            return
        keys = [it.get('key') for it in sel]
        if not messagebox.askyesno(
                '确认删除',
                '将删除桶中 {0} 个对象（不可恢复）:\n{1}'.format(
                    len(keys), '\n'.join(keys[:8]) + ('\n…' if len(keys) > 8 else '')),
                parent=self):
            return
        self._start_op('正在删除 {0} 个对象…'.format(len(keys)))
        self.app.browser_delete(keys)
