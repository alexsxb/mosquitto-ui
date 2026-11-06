import i18n from "i18next"
import LanguageDetector from "i18next-browser-languagedetector"
import { initReactI18next } from "react-i18next"

import de from "./de"
import en from "./en"
import fr from "./fr"

export const languages = ["de", "en", "fr"] as const
export type Language = (typeof languages)[number]

declare module "i18next" {
  interface CustomTypeOptions {
    resources: { translation: typeof en }
  }
}

void i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: { en: { translation: en }, de: { translation: de }, fr: { translation: fr } },
    supportedLngs: languages,
    nonExplicitSupportedLngs: true,
    fallbackLng: "en",
    interpolation: { escapeValue: false },
    detection: { order: ["localStorage", "navigator"], caches: ["localStorage"], lookupLocalStorage: "mosquitto-ui.language" },
  })

i18n.on("languageChanged", (lng) => {
  document.documentElement.lang = lng
})

export default i18n
