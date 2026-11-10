# -*- coding: utf-8 -*-
"""应用入口：装配托盘 + 主窗口 + 定时同步引擎。

线程模型:
  - 主线程: tkinter mainloop + root.after(150) 轮询事件队列（唯一允许操作 tk 的线程）
  - 托盘线程: pystray icon.run()，菜单回调只投递事件
  - 工作线程: ThreadPoolExecutor(max_workers=1) 串行执行所有七牛网络操作
"""
import logging
import logging.handlers
import os
import queue
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import tkinter as tk
from tkinter import ttk, messagebox

from app import config
from app import state as state_mod
from app.sync import run_once, local_target, norm_prefix
from app.tray import make_icon
from app.qiniu_client import UP_HOSTS

log = logging.getLogger('clockin')


class QueueLogHandler(logging.Handler):
    def __init__(self, q, fmt):
        super().__init__()
        self.q = q
        self.fmt = fmt

    def emit(self, record):
        try:
            self.q.put_nowait(('log', self.fmt.format(record)))
        except Exception:
            pass


class App:
    def __init__(self):
        self.cfg = config.load()
        self.events = queue.Queue()
        self.executor = ThreadPoolExecutor(max_workers=1)
        self.paused = False
        self._sync_busy = False
        self._after_id = None
        self._last_sync = None

        self._fmt = logging.Formatter('%(asctime)s %(levelname)s %(message)s', '%H:%M:%S')
        self._setup_logging()

        self.root = tk.Tk()
        self._set_window_icon()
        self.root.withdraw()  # 先隐藏，构建完成后若需要再显示
        self.notebook = ttk.Notebook(self.root)
        self.status_var = tk.StringVar(value='准备就绪')
        self.pages = {}
        self.badge_label = None
        self.mode_label = None

        from app.gui.main_window import build
        build(self)
        self._refresh_badge()

        self.icon = make_icon(self.post_event, lambda: self.paused)
        threading.Thread(target=self.icon.run, daemon=True, name='tray').start()

        self.root.after(150, self.poll)
        self._first_start_hint()
        self.schedule_next()
        log.info('程序已启动（配置: %s）',
                 '完整' if config.is_ready(self.cfg) else '不完整，请先到设置页填写')

    # ---------- 基础 ----------

    def _set_window_icon(self):
        """标题栏与任务栏图标：设置 AppUserModelID 避免被归到 python 下，并加载自定义 ico。"""
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID('S3FileSync')
        except Exception:
            pass
        ico = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icon.ico')
        if os.path.exists(ico):
            try:
                self.root.iconbitmap(ico)
            except Exception as e:
                log.warning('设置窗口图标失败: %s', e)

    def _setup_logging(self):
        os.makedirs(config.APP_DIR, exist_ok=True)
        root_logger = logging.getLogger()
        root_logger.setLevel(logging.INFO)
        root_logger.addHandler(QueueLogHandler(self.events, self._fmt))
        fh = logging.handlers.RotatingFileHandler(
            os.path.join(config.APP_DIR, 'app.log'),
            maxBytes=1024 * 1024, backupCount=2, encoding='utf-8')
        fh.setFormatter(self._fmt)
        root_logger.addHandler(fh)

    def _first_start_hint(self):
        if config.is_ready(self.cfg):
            self.root.deiconify()
        else:
            self.root.deiconify()
            log.info('首次使用: 请在"设置"页填写 AK/SK/桶名/本地目录后保存。')

    def post_event(self, name):
        self.events.put((name,))

    def submit(self, fn):
        def job():
            try:
                fn()
            except Exception:
                log.exception('后台任务异常')
        self.executor.submit(job)

    # ---------- 调度 ----------

    def schedule_next(self, keep_status=False):
        if self._after_id:
            try:
                self.root.after_cancel(self._after_id)
            except Exception:
                pass
            self._after_id = None
        if self.paused:
            self.status_var.set('自动同步已暂停')
            return
        if not config.is_ready(self.cfg):
            self._after_id = self.root.after(30000, self._recheck_ready)
            return
        interval = max(1, int(self.cfg.get('interval_minutes', 30))) * 60000
        nxt = time.strftime('%H:%M:%S', time.localtime(time.time() + interval / 1000))
        self._after_id = self.root.after(interval, self._on_timer)
        if not self._sync_busy:
            if keep_status:
                cur = self.status_var.get().strip()
                self.status_var.set('{0} · 下次 {1}'.format(cur, nxt))
            else:
                self.status_var.set('下次自动同步: {0}'.format(nxt))

    def _recheck_ready(self):
        self._after_id = None
        self.schedule_next()

    def _on_timer(self):
        self._after_id = None
        self.start_sync()

    def start_sync(self, mode=None):
        """mode=None 用配置里的默认方向；否则临时单跑指定方向。"""
        if self._sync_busy:
            log.info('上一轮同步尚未结束，跳过本次触发')
            return
        if not config.is_ready(self.cfg):
            self.schedule_next()
            return
        mode = mode or self.cfg.get('sync_mode', 'download')
        self._sync_busy = True
        label = config.SYNC_MODES.get(mode, mode)
        self.status_var.set('正在同步（{0}）…'.format(label))
        log.info('开始同步：%s', label)
        self.submit(lambda: self._sync_job(mode))

    def _sync_job(self, mode):
        try:
            summary = run_once(self.cfg, mode=mode)
        except Exception as e:
            log.exception('同步失败')
            summary = {'mode': mode, 'total': 0, 'local_total': 0, 'new': [],
                       'uploaded': [], 'downloaded': [], 'failed': [],
                       'skipped': 0, 'error': str(e)}
        self.events.put(('sync_done', summary))

    # ---------- 事件处理（主线程） ----------

    def poll(self):
        try:
            try:
                while True:
                    ev = self.events.get_nowait()
                    self.handle_event(ev)
            except queue.Empty:
                pass
            self.root.after(150, self.poll)
        except Exception:
            # 窗口已销毁等场景：不再排程，静默退出轮询
            pass

    def handle_event(self, ev):
        name = ev[0]
        if name == 'show':
            self.root.deiconify()
            self.root.lift()
        elif name == 'sync_now':
            self.start_sync()
        elif name == 'sync_upload':
            self.start_sync('upload')
        elif name == 'sync_download':
            self.start_sync('download')
        elif name == 'open_cloud':
            self.open_cloud_dir()
        elif name == 'region_detected':
            self._apply_detected_region(ev[1])
        elif name == 'toggle_pause':
            self.paused = not self.paused
            log.info('自动同步已%s', '暂停' if self.paused else '恢复')
            self._refresh_badge()
            self.schedule_next()
        elif name == 'open_dir':
            self.open_download_dir()
        elif name == 'quit':
            self.quit()
        elif name == 'log':
            page = self.pages.get('log')
            if page:
                page.append(ev[1])
        elif name == 'sync_done':
            self._on_sync_done(ev[1])
        elif name == 'test_done':
            ok, msg = ev[1], ev[2]
            title = ev[3] if len(ev) > 3 else '测试连接'
            if ok:
                messagebox.showinfo(title, msg, parent=self.root)
            else:
                messagebox.showerror(title, msg, parent=self.root)
        elif name == 'list_done':
            items, next_marker, req_marker = ev[1], ev[2], ev[3]
            page = self.pages.get('browser')
            if page:
                page.fill(items, next_marker, req_marker)
        elif name == 'list_failed':
            page = self.pages.get('browser')
            if page:
                page.show_error(ev[1])
        elif name == 'op_done':
            self._on_op_done(ev[1:])

    def _on_sync_done(self, summary):
        self._sync_busy = False
        self._last_sync = time.time()
        n_up = len(summary.get('uploaded', []))
        n_down = len(summary.get('downloaded', []))
        n_fail = len(summary.get('failed', []))
        mode = summary.get('mode', 'download')
        label = config.SYNC_MODES.get(mode, mode).split('（')[0]
        if summary.get('error'):
            log.error('同步出错: %s', summary['error'])
        log.info('同步完成[%s]: 上传 %d / 下载 %d / 跳过 %d / 失败 %d / 云端 %d / 本地 %d',
                 label, n_up, n_down, summary.get('skipped', 0), n_fail,
                 summary.get('total', 0), summary.get('local_total', 0))
        for key, err in summary.get('failed', []):
            log.warning('  失败: %s (%s)', key, err)
        self.status_var.set('同步完成 {0} [{1}]: 上传 {2} / 下载 {3} / 失败 {4}'.format(
            time.strftime('%H:%M:%S'), label, n_up, n_down, n_fail))
        if summary.get('detected_region'):
            self._apply_detected_region(summary['detected_region'])
        self.schedule_next(keep_status=True)
        try:
            if n_up or n_down or n_fail:
                self.icon.notify('上传 {0} / 下载 {1} / 失败 {2}'.format(n_up, n_down, n_fail),
                                 config.APP_NAME)
        except Exception:
            pass

    def _on_op_done(self, args):
        tag = args[0]
        if tag == 'delete':
            errs, keys = args[1], args[2]
            log.info('删除完成: 成功 %d / 失败 %d', len(keys) - len(errs), len(errs))
            for k, e in errs:
                log.warning('  删除失败: %s (%s)', k, e)
            messagebox.showinfo('删除', '成功 {0} 个，失败 {1} 个。'.format(
                len(keys) - len(errs), len(errs)), parent=self.root)
            page = self.pages.get('browser')
            if page:
                page.finish_op('删除完成: 成功 {0} / 失败 {1}'.format(
                    len(keys) - len(errs), len(errs)))
                page.refresh()
        elif tag == 'download':
            errs, saved = args[1], args[2]
            log.info('手动下载完成: 成功 %d / 失败 %d', len(saved), len(errs))
            for k, e in errs:
                log.warning('  下载失败: %s (%s)', k, e)
            msg = '成功 {0} 个:\n{1}'.format(len(saved), '\n'.join(saved[:8]) +
                                            ('\n…' if len(saved) > 8 else ''))
            if errs:
                msg += '\n\n失败 {0} 个（详见日志）'.format(len(errs))
            messagebox.showinfo('下载', msg, parent=self.root)
            self.status_var.set('手动下载完成: 成功 {0}'.format(len(saved)))
            page = self.pages.get('browser')
            if page:
                page.finish_op('下载完成: 成功 {0} / 失败 {1}'.format(len(saved), len(errs)))
        elif tag == 'upload':
            errs, keys = args[1], args[2]
            prefix = args[3] if len(args) > 3 else None
            log.info('手动上传完成: 成功 %d / 失败 %d', len(keys), len(errs))
            for k, e in errs:
                log.warning('  上传失败: %s (%s)', k, e)
            msg = '成功上传 {0} 个对象到 {1}:\n{2}'.format(
                len(keys), prefix or '桶根目录',
                '\n'.join(keys[:8]) + ('\n…' if len(keys) > 8 else ''))
            if errs:
                msg += '\n\n失败 {0} 个（详见日志）'.format(len(errs))
            messagebox.showinfo('上传', msg, parent=self.root)
            self.status_var.set('手动上传完成: 成功 {0}'.format(len(keys)))
            page = self.pages.get('browser')
            if page:
                page.finish_op('上传完成: 成功 {0} / 失败 {1}'.format(len(keys), len(errs)))
                if prefix is not None:
                    page.v_prefix.set(prefix)
                page.refresh()

    # ---------- 浏览/测试任务 ----------

    def _client(self, data=None):
        from app.qiniu_client import QiniuKodo
        cfg = data or self.cfg
        return QiniuKodo(cfg['ak'], cfg['sk'], cfg['bucket'],
                         cfg.get('region', 'z0'), cfg.get('download_domain', ''),
                         cfg.get('proxy_mode', 'auto'))

    def browser_list(self, prefix, marker):
        def job():
            try:
                c = self._client()
                items, next_marker, _eof = c.list_page(prefix, marker, 100)
                self.events.put(('list_done', items, next_marker, marker))
            except Exception as e:
                log.error('列举对象失败: %s', e)
                self.events.put(('list_failed', str(e), marker))
        self.submit(job)

    def browser_delete(self, keys):
        def job():
            c = self._client()
            errs = []
            for k in keys:
                try:
                    c.delete(k)
                except Exception as e:
                    errs.append((k, str(e)))
            self.events.put(('op_done', 'delete', errs, keys))
        self.submit(job)

    def browser_download(self, entries):
        def job():
            c = self._client()
            root_dir = self.cfg.get('download_dir') or os.path.expanduser('~')
            errs, saved = [], []
            for key, _fsize in entries:
                try:
                    dest = local_target(root_dir, key)
                    c.download(key, dest)
                    saved.append(dest)
                except Exception as e:
                    errs.append((key, str(e)))
            self.events.put(('op_done', 'download', errs, saved))
        self.submit(job)

    def browser_upload(self, specs, prefix=''):
        """手动上传：specs 为 [(本地路径, 云端 key)]，允许覆盖同名对象。

        上传成功后写入同步状态，之后的自动同步不会重复处理这些文件。
        """
        def job():
            c = self._client()
            state = state_mod.load()
            errs, keys = [], []
            for path, key in specs:
                try:
                    rh = c.upload_file(path, key)
                    state_mod.record(state, key, rh, os.path.getsize(path),
                                     os.path.abspath(path))
                    state_mod.save(state)
                    keys.append(key)
                    log.info('手动上传: %s -> %s', path, key)
                except Exception as e:
                    errs.append((key, str(e)))
                    log.warning('手动上传失败: %s (%s)', key, e)
            self.events.put(('op_done', 'upload', errs, keys, prefix))
        self.submit(job)

    def test_connection(self, data):
        def job():
            try:
                c = self._client(data)
                prefix = norm_prefix(data.get('prefix', ''))
                items, _m, _e = c.list_page(prefix, limit=5)
                domain = c.resolve_domain()
                msg = '连接成功。\nBucket: {0}\n下载域名: {1}\n当前云端目录: {2}\n本页对象数: {3}'.format(
                    c.bucket, domain, prefix or '整个桶', len(items))
                msg += '\n网络模式: {0}'.format('直连（忽略系统代理）' if c.direct else '系统代理')
                msg += '\n同步方向: {0}'.format(
                    config.SYNC_MODES.get(data.get('sync_mode', 'download'), '仅下载'))
                if items:
                    code = c.probe_private_url(items[0]['key'])
                    msg += '\n私有下载测试: {0}'.format('OK' if code == 200 else
                                                        '失败 HTTP {0}'.format(code))
                self.events.put(('test_done', True, msg))
            except Exception as e:
                self.events.put(('test_done', False,
                                 '连接失败: {0}\n\n若提示代理错误，可到设置页把「网络代理」改为「直连」。'
                                 .format(e)))
        self.submit(job)

    def test_upload(self, data):
        """真实上传一个极小的临时对象并立即删除，验证上传链路。"""
        def job():
            import tempfile
            prefix = norm_prefix(data.get('prefix', ''))
            key = prefix + '_selftest_qiniu_sync.tmp'
            tmp = None
            try:
                fd, tmp = tempfile.mkstemp(prefix='qsync_', suffix='.txt')
                with os.fdopen(fd, 'w', encoding='utf-8') as f:
                    f.write('qiniu file sync selftest')
                c = self._client(data)
                rh = c.upload_file(tmp, key)
                if c.detected_region:
                    self.events.put(('region_detected', c.detected_region))
                cleaned = True
                try:
                    c.delete(key)
                except Exception as de:
                    cleaned = False
                    log.warning('清理上传自检对象失败: %s', de)
                self.events.put(('test_done', True,
                                 '上传成功。\n测试对象: {0}\n云端 hash: {1}\n'
                                 '上传端点: {2}\n实际区域: {3}（设置值 {4}）\n'
                                 '测试对象已清理: {5}'.format(
                                     key, rh, c.up_host,
                                     c.detected_region or data.get('region', '?'),
                                     data.get('region', '?'),
                                     '是' if cleaned else '否（请到控制台手动删除）'),
                                 '上传自检'))
            except Exception as e:
                self.events.put(('test_done', False, '上传测试失败: {0}'.format(e),
                                 '上传自检'))
            finally:
                if tmp:
                    try:
                        os.remove(tmp)
                    except OSError:
                        pass
        self.submit(job)

    # ---------- 其他 ----------

    def apply_settings(self, data):
        old_prefix = norm_prefix(self.cfg.get('prefix', ''))
        self.cfg.update(data)
        config.save(self.cfg)
        log.info('设置已保存（空间: %s，区域: %s，方向: %s，云端目录: %s，间隔: %s 分钟，网络: %s）',
                 self.cfg.get('bucket'), self.cfg.get('region'),
                 config.SYNC_MODES.get(self.cfg.get('sync_mode'), '仅下载'),
                 norm_prefix(self.cfg.get('prefix', '')) or '整个空间',
                 self.cfg.get('interval_minutes'), self.cfg.get('proxy_mode', 'auto'))
        self._refresh_badge()
        new_prefix = norm_prefix(self.cfg.get('prefix', ''))
        if new_prefix != old_prefix:
            page = self.pages.get('browser')
            if page:
                page.v_prefix.set(new_prefix)
                page.loaded = False
                try:
                    if self.notebook.index('current') == 1:
                        page.refresh()
                except Exception:
                    pass
        self.schedule_next()

    def open_local_dir(self, path=None):
        """在资源管理器里打开本地目录；目录不存在时询问是否创建。"""
        d = path or self.cfg.get('download_dir')
        if not d:
            messagebox.showwarning('本地目录', '请先在设置页填写本地目录。', parent=self.root)
            return
        if not os.path.isdir(d):
            if not messagebox.askyesno('目录不存在',
                                       '目录还不存在，是否创建？\n{0}'.format(d),
                                       parent=self.root):
                return
            try:
                os.makedirs(d, exist_ok=True)
            except OSError as e:
                messagebox.showerror('错误', '无法创建目录: {0}'.format(e), parent=self.root)
                return
        try:
            os.startfile(d)
        except Exception as e:
            log.warning('打开本地目录失败: %s', e)
            messagebox.showerror('错误', str(e), parent=self.root)

    def open_download_dir(self):
        self.open_local_dir()

    def open_cloud_dir(self, prefix=None):
        """在浏览器打开七牛云控制台的当前云端目录。"""
        import webbrowser
        from urllib.parse import quote
        bucket = self.cfg.get('bucket', '')
        if not bucket:
            messagebox.showwarning('云端目录', '请先在设置页填写 Bucket 名称。', parent=self.root)
            return
        p = norm_prefix(prefix if prefix is not None else self.cfg.get('prefix', ''))
        url = ('https://portal.qiniu.com/kodo/bucket/resource?bucket=' +
               quote(bucket, safe=''))
        if p:
            url += '&prefix=' + quote(p, safe='')
        webbrowser.open(url)
        self.status_var.set('已在浏览器打开云端目录: {0}'.format(p or '桶根目录'))

    def focus_browser(self, prefix=None):
        """切到文件浏览页并按给定云端目录重新列举。"""
        page = self.pages.get('browser')
        if not page:
            return
        if prefix is not None:
            page.v_prefix.set(norm_prefix(prefix))
        self.root.deiconify()
        self.root.lift()
        try:
            self.notebook.select(1)
        except Exception:
            pass
        page.loaded = False
        page.refresh()

    def _apply_detected_region(self, reg):
        """上传时发现桶的真实区域与配置不符 → 更新配置并同步设置页下拉。"""
        if not reg or reg not in UP_HOSTS or reg == self.cfg.get('region'):
            return
        old = self.cfg.get('region')
        log.warning('空间实际区域为 %s（原设置 %s），已自动更新配置', reg, old)
        self.cfg['region'] = reg
        config.save(self.cfg)
        sp = self.pages.get('settings')
        if sp is not None:
            try:
                sp.v_region.set(reg)
            except Exception:
                pass
        self.status_var.set('{0} · 区域已修正为 {1}'.format(
            self.status_var.get().strip(), reg))

    def _refresh_badge(self):
        """更新顶部运行状态与默认方向徽标（由主线程调用）。"""
        mode = self.cfg.get('sync_mode', 'download')
        mlbl = getattr(self, 'mode_label', None)
        if mlbl is not None:
            mlbl.configure(text=config.SYNC_MODES.get(mode, '仅下载').split('（')[0])
        lbl = getattr(self, 'badge_label', None)
        if lbl is None:
            return
        if not config.is_ready(self.cfg):
            lbl.configure(text='● 配置待完善', style='PillWarn.TLabel')
        elif self.paused:
            lbl.configure(text='● 自动同步已暂停', style='PillWarn.TLabel')
        else:
            lbl.configure(text='● 自动同步运行中', style='Pill.TLabel')

    def hide_window(self):
        self.root.withdraw()
        log.info('窗口已隐藏到托盘（通过托盘菜单退出程序）')

    def quit(self):
        log.info('程序退出')
        try:
            self.icon.stop()
        except Exception:
            pass
        self.executor.shutdown(wait=False)
        self.root.destroy()


def main():
    app = App()
    app.root.mainloop()


if __name__ == '__main__':
    main()
