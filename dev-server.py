# -*- coding: utf-8 -*-
"""
MARS CONCERT 本地开发服务器（替代 python -m http.server）
HTML 禁缓存（保证页面跳转后总是加载最新代码），静态资源允许缓存
用法: python dev-server.py  （端口 8765，根目录 mars_concert_site/）
"""
import http.server
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mars_concert_site')
PORT = 8765


class DevHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        path = self.translate_path(self.path).replace('\\', '/').lower()
        if path.endswith('.html') or path.endswith('/') or '?' in self.path:
            # HTML 禁缓存：多页站播放器跨页续播依赖最新 JS 引用
            self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
            self.send_header('Pragma', 'no-cache')
            self.send_header('Expires', '0')
        super().end_headers()


if __name__ == '__main__':
    server = http.server.ThreadingHTTPServer(('0.0.0.0', PORT), DevHandler)
    print(f'MARS CONCERT dev server: http://localhost:{PORT}  (HTML no-cache)')
    server.serve_forever()
