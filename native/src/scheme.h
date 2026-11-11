// The vitrine:// scheme. Must be registered identically in every CEF process.
#pragma once
#include "include/cef_scheme.h"

inline void RegisterVitrineScheme(CefRawPtr<CefSchemeRegistrar> registrar) {
  // Standard + secure: real origins (vitrine://<modid>), secure context, fetch()/CORS like https.
  registrar->AddCustomScheme("vitrine", CEF_SCHEME_OPTION_STANDARD | CEF_SCHEME_OPTION_SECURE |
                                            CEF_SCHEME_OPTION_CORS_ENABLED |
                                            CEF_SCHEME_OPTION_FETCH_ENABLED);
}
