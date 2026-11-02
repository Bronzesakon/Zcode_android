# 3.16.1 登录相关关键字字节偏移索引

**用途：** 快速定位登录/认证代码。偏移是 **UTF-8 字节偏移**，只在下列文件与 SHA-256
完全一致时有效。复现方式：

```js
const b = require('fs').readFileSync('Reference_web/3.16.1/assets/index-DbEHT6Gg.js');
console.log(b.indexOf('auth_init')); // 6746350
```

| 文件 | 大小（B） | SHA-256 |
|---|---:|---|
| `assets/index-DbEHT6Gg.js` | 6,836,503 | `dc0d8128b394d624a003af8c9afa22f77c9dbcdc30fbabef15b85df605e3c821` |
| `assets/src-uVqBd_Eq.js` | 70,714 | `93813e7e2b9ff43625fced61589000aed65788d3e65e66d4ace6f7494bcaaa91` |
| `assets/validation-BBf1FA1a.js` | 331,153 | `7686853747e58b524a84a15ddff6cd5fd92b1870ea7dc86781888c7b294d8962` |
| `assets/IntlProvider-Be9akOJ8.js` | 932,634 | `3cc6ba5331dc6025214d55e64c3a1d3115eb4f9324eadf5621ab3a9abc0ec0d6` |

## 主包 `index-DbEHT6Gg.js`

| 关键字 | 字节偏移 |
|---|---:|
| `loginTitle` | 6,705,353 |
| `loginAction` | 6,134,630 |
| `loginRegion` | 6,705,579 |
| `exchangeToken` | 6,711,737 |
| `/api/v1/oauth/token` | 6,718,180 |
| `app_return_to` | 6,716,606 |
| `auth_init` | 6,746,350 |
| `auth_ack` | 6,754,831 |
| `pair_status` | 6,746,283 |
| `pair_status_query` | 6,757,298 |
| `pair_status_ack` | 6,746,283 |
| `device_sid` | 6,751,416 |
| `role:\`terminal\`` | 6,762,925 |
| `AUTH_FAILED` | 6,756,541 |
| `WRONG_PARAM` | 6,756,560 |
| `KICKED` | 6,756,323 |
| `INTERNAL` | 6,756,685 |
| `DEVICE_OFFLINE` | 6,756,473 |
| `bootstrap-response` | 6,823,539 |
| `workspace-list` | 5,861,639 |
| `completedSuccess` | 2,026,631 |
| `lastActivityAt` | 1,698,326 |
| `lastAssistantPreview` | 未在主包命中（见 validation 文件） |
| `zcodejwttoken` | 2,446,609 |
| `localStorage` | 232,536 |
| `sessionStorage` | 1,914,607 |
| `nonce` | 420,490 |
| `redirect_uri` | 97,402 |
| `client_P8X5CMWmlaRO9gyO-KSqtg` | 231,846 |
| `expires_in` | 97,009 |
| `bigmodel` | 71,661 |
| `zai` | 71,637 |
| `terminal` | 25,202 |
| `device_mid` | 2,551,692 |
| `登录后继续使用` | 6,705,365 |
| `请选择与电脑端相同的登录平台` | 6,705,423 |
| `连接 Z.ai` | 6,705,512 |
| `连接 BigModel` | 6,705,548 |

## `src-uVqBd_Eq.js`（remote / token schema）

| 关键字 | 字节偏移 |
|---|---:|
| `AUTH_FAILED` | 52,949 |
| `WRONG_PARAM` | 52,963 |
| `KICKED` | 52,977 |
| `INTERNAL` | 52,986 |
| `DEVICE_OFFLINE` | 52,997 |
| `auth_init` | 53,036 |
| `device_sid` | 53,048 |
| `pair_status` | 53,117 |
| `pair_status_query` | 53,117 |
| `auth_ack` | 53,248 |
| `pair_status_ack` | 53,352 |
| `/api/v1/remote/token` | 53,834 |
| `remote/token` | 53,842 |
| `device_mid` | 53,861 |
| `expires_in` | 54,044 |
| `zcodejwttoken` | 63,207 |
| `localStorage` | 63,432 |
| `terminal` | 28,991 |
| `bigmodel` | 30,666 |
| `zai` | 39,136 |

## `validation-BBf1FA1a.js`（zod / 共享 schema）

| 关键字 | 字节偏移 |
|---|---:|
| `remote/ws2` | 125,393 |
| `bigmodel` | 98,959 |
| `zai` | 98,945 |
| `terminal` | 140,298 |
| `device_mid` | 146,459 |
| `bootstrap-response` | 305,408 |
| `workspace-list` | 305,484 |
| `completedSuccess` | 209,723 |
| `lastActivityAt` | 248,618 |
| `lastAssistantPreview` | 248,635 |

## `IntlProvider-Be9akOJ8.js`（文案）

| 关键字 | 字节偏移 |
|---|---:|
| `连接 Z.ai` | 76,171 |
| `连接 BigModel` | 76,228 |
| `bigmodel` | 76,217 |
| `zai` | 76,165 |
| `terminal` | 86,535 |
| `localStorage` | 229 |

> 说明：`lastAssistantPreview` 在主包未命中，但在 `validation` 的 sessions-index schema 中；
> 实际读取代码可能通过压缩后的属性名或字符串拼接访问。审计报告应以 schema 与调用点为准。
