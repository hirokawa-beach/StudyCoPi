const CACHE_NAME = "studycopi-v2";
const ASSETS = [
  "./",
  "./index.html",
  "./app.js",
  "./styles.css",
  "./manifest.json",
  "./icons/icon-192.png",
  "./icons/icon-512.png",
];

// インストール時にアセットをキャッシュ
self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS)));
  self.skipWaiting();
});

// 古いキャッシュを削除
self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)),
        ),
      ),
  );
  self.clients.claim();
});

// Cache-first：キャッシュにあればそれを返し、なければネットワーク取得してキャッシュ
self.addEventListener("fetch", (e) => {
  // chrome-extension など無関係なリクエストは無視
  if (!e.request.url.startsWith("http")) return;

  e.respondWith(
    caches.match(e.request).then((cached) => {
      if (cached) return cached;
      return fetch(e.request).then((res) => {
        // 正常レスポンスのみキャッシュ
        if (res && res.status === 200 && res.type === "basic") {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, clone));
        }
        return res;
      });
    }),
  );
});

// 通知タップ時：既に開いているタブがあればフォーカス、なければ新規で開く
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const targetUrl =
    (e.notification.data && e.notification.data.url) || "./index.html";

  e.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((clientList) => {
        for (const client of clientList) {
          if ("focus" in client) return client.focus();
        }
        if (self.clients.openWindow) return self.clients.openWindow(targetUrl);
      }),
  );
});
