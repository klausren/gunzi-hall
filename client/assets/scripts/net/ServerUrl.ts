/**
 * 战斗服地址解析（T-503 配套）。
 *
 * ## 为什么需要它
 *
 * `serverUrl` 原是**硬编码的局域网 IP**（如 `ws://10.192.1.110:8080/ws`），散落在
 * `main.scene` / `TableUI.ts` / `NetTest.ts` 三处。换 WiFi、换网卡、换个教室，
 * IP 一变客户端就再也连不上，必须改三处源码 + 重新构建——这是反复踩的坑。
 *
 * 现在改为**运行时按优先级解析**，Web 端不再依赖写死的 IP。
 *
 * ## 优先级
 *
 * 1. **URL 查询参数** `?server=ws://host:port/ws`
 *    联调/排查时临时指向别的后端，不用改代码、不用重新构建。
 *
 * 2. **从 `location` 推导**
 *    页面从哪台机器加载，就默认连哪台机器的后端。
 *    手机访问 `http://10.192.6.212:8081` → 自动连 `ws://10.192.6.212:8080`。
 *    **这是本模块的主要价值**：换网络自动跟随，无需任何改动。
 *
 * 3. **组件配置的 `serverUrl`**（兜底）
 *    给没有 `location` 的微信小游戏环境，以及 Cocos 编辑器预览用。
 *    构建脚本 `build-wechatgame.ps1` 会在构建时把本机 IP 注入到这里。
 *
 * ## 各环境实际取值
 *
 * | 环境 | location | 结果 |
 * |---|---|---|
 * | 浏览器 `http://127.0.0.1:8081` | 有 | `ws://127.0.0.1:8080/ws` |
 * | 手机 `http://10.192.6.212:8081` | 有 | `ws://10.192.6.212:8080/ws` |
 * | 微信小游戏 | 无 | 配置值（构建时注入） |
 * | Cocos 编辑器预览 | 有（localhost） | `ws://localhost:8080/ws` |
 */

/** 战斗服默认端口（与 `game-room` 的 `ServerMain` 保持一致） */
export const SERVER_PORT = 8080;

/** 战斗服 WebSocket 路径 */
export const SERVER_PATH = '/ws';

/** URL 查询参数名，用于临时覆盖后端地址 */
export const SERVER_QUERY_KEY = 'server';

/**
 * 取 URL 查询参数里显式指定的地址。
 * 例：`http://localhost:8081/?server=ws://10.0.0.5:8080/ws`
 */
function fromQuery(): string | null {
    if (typeof location === 'undefined') return null;
    const search = location.search;
    if (!search) return null;

    // 手写解析而不用 URLSearchParams：小游戏环境不保证有该 API
    const m = new RegExp('[?&]' + SERVER_QUERY_KEY + '=([^&]+)').exec(search);
    if (!m || !m[1]) return null;

    const raw = m[1];
    let url: string;
    try {
        url = decodeURIComponent(raw);
    } catch {
        url = raw; // 含非法转义字符时按原样使用
    }
    return url.trim() || null;
}

/**
 * 从当前页面地址推导：同主机 + 后端端口 + /ws。
 * `file://` 打开时 hostname 为空，返回 null 交给兜底。
 */
function fromLocation(): string | null {
    if (typeof location === 'undefined') return null;
    const host = location.hostname;
    if (!host) return null;

    // https 页面下建 ws:// 会被浏览器按混合内容拦截，故随页面协议切换
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    return `${scheme}://${host}:${SERVER_PORT}${SERVER_PATH}`;
}

/**
 * 解析最终要连接的战斗服地址。
 *
 * @param configured 组件/场景上配置的兜底地址（微信小游戏与编辑器预览会用到）
 * @returns 实际连接地址
 */
export function resolveServerUrl(configured: string): string {
    const fromQ = fromQuery();
    if (fromQ) return fromQ;

    const fromL = fromLocation();
    if (fromL) return fromL;

    return configured;
}

/**
 * 供 UI 展示「连的是哪台机器」以及「地址从哪来」，便于排查连不上。
 *
 * @returns 形如 `ws://10.192.6.212:8080/ws`（来源：自动探测）的字符串
 */
export function describeServerUrl(configured: string): string {
    if (fromQuery()) return `${resolveServerUrl(configured)}（来源：URL 参数 ?server=）`;
    if (fromLocation()) return `${resolveServerUrl(configured)}（来源：自动探测）`;
    return `${configured}（来源：配置值）`;
}
