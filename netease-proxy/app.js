/**
 * Mars Concert · 网易云音乐 API 代理（加密版）
 * 基于 NeteaseCloudMusicApi v4，内置 weapi/eapi 加密
 *
 * 用法: npm start  或  node app.js
 * 端口: 3000（已内置 CORS，浏览器可直接跨域调用）
 */
const { serveNcmApi } = require('NeteaseCloudMusicApi');

const PORT = process.env.PORT || 3000;

serveNcmApi({
  port: Number(PORT),
  // 允许跨域（默认已开启，这里显式声明）
}).then(() => {
  console.log('');
  console.log('  ╔══════════════════════════════════════════════╗');
  console.log('  ║  Mars Concert · 网易云音乐 API（加密版）       ║');
  console.log('  ╠══════════════════════════════════════════════╣');
  console.log('  ║  地址: http://localhost:' + PORT + '                  ║');
  console.log('  ║  状态: 运行中 (weapi/eapi 加密已启用) ✓        ║');
  console.log('  ╚══════════════════════════════════════════════╝');
  console.log('');
  console.log('  前端可用接口:');
  console.log('  ├─ 发送验证码  GET /captcha/sent?phone=138xxx');
  console.log('  ├─ 验证码登录  GET /login/cellphone?phone=138xxx&captcha=123456');
  console.log('  ├─ 密码登录    GET /login/cellphone?phone=138xxx&md5_pass=xxx');
  console.log('  ├─ 搜索歌曲    GET /cloudsearch?keywords=华晨宇 拆弹专家');
  console.log('  ├─ 获取音源    GET /song/url?id=29418040');
  console.log('  ├─ 二维码 key  GET /login/qr/key');
  console.log('  ├─ 二维码生成  GET /login/qr/create?key=xxx&qrimg=true');
  console.log('  └─ 二维码检测  GET /login/qr/check?key=xxx');
  console.log('');
});
