// CEF subprocess (renderer, GPU, utility). Must sit next to libcef.dll.
#include <windows.h>
#include "include/cef_app.h"
#include "scheme.h"

class HelperApp : public CefApp {
 public:
  void OnRegisterCustomSchemes(CefRawPtr<CefSchemeRegistrar> registrar) override {
    RegisterVitrineScheme(registrar);
  }
  IMPLEMENT_REFCOUNTING(HelperApp);
};

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE, LPWSTR, int) {
  CefMainArgs args(instance);
  return CefExecuteProcess(args, new HelperApp(), nullptr);
}
