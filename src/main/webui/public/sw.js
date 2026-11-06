// Service worker of mosquitto-ui. It makes the app installable and faster but keeps no data: everything about the broker
// always comes fresh from the server. Cached are only the built files under /assets/ (hash in the name, they never change),
// the icons and the page for "no connection". Pages, the login and the API always go to the network.
const CACHE = "mosquitto-ui-v1"
const OFFLINE = ["/offline.html", "/icons/icon-192.png"]

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(OFFLINE)))
  self.skipWaiting()
})

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener("fetch", (event) => {
  const { request } = event
  const url = new URL(request.url)
  if (request.method !== "GET" || url.origin !== self.location.origin) {
    return
  }

  // a page: always from the network, so a new version is there at once; without network the notice
  if (request.mode === "navigate") {
    event.respondWith(fetch(request).catch(() => caches.match("/offline.html")))
    return
  }

  // built files and icons: from the cache, otherwise fetch and keep
  if (url.pathname.startsWith("/assets/") || url.pathname.startsWith("/icons/")) {
    event.respondWith(
      caches.match(request).then(
        (hit) =>
          hit ??
          fetch(request).then((response) => {
            if (response.ok) {
              const copy = response.clone()
              void caches.open(CACHE).then((cache) => cache.put(request, copy))
            }
            return response
          }),
      ),
    )
  }
})
