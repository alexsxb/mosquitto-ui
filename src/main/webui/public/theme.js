// Apply the theme before the first paint to avoid a flash of the light theme; a file of its own, as the
// Content-Security-Policy allows no inline scripts
try {
  const stored = localStorage.getItem("mosquitto-ui.theme")
  const dark = stored ? stored === "dark" : matchMedia("(prefers-color-scheme: dark)").matches
  document.documentElement.classList.toggle("dark", dark)
} catch {}
