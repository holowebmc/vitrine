cmake_minimum_required(VERSION 3.21)
project(vitrine CXX)

set(USE_SANDBOX OFF CACHE BOOL "" FORCE)
set(CEF_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/cef")
list(APPEND CMAKE_MODULE_PATH "${CEF_ROOT}/cmake")
find_package(CEF REQUIRED)
find_package(JNI REQUIRED)

add_subdirectory(${CEF_LIBCEF_DLL_WRAPPER_PATH} libcef_dll_wrapper)

# Runtime folder Minecraft loads from (dev: -Dvitrine.cefDir points here).
set(OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/out")

ADD_LOGICAL_TARGET("libcef_lib" "${CEF_LIB_RELEASE}" "${CEF_LIB_RELEASE}")

add_library(vitrine SHARED src/vitrine.cpp src/gpu.cpp)
SET_LIBRARY_TARGET_PROPERTIES(vitrine)
target_include_directories(vitrine PRIVATE ${CEF_ROOT} ${JNI_INCLUDE_DIRS})
target_link_libraries(vitrine libcef_lib libcef_dll_wrapper ${CEF_STANDARD_LIBS} d3d11 dxgi d3dcompiler opengl32)
add_dependencies(vitrine libcef_dll_wrapper)

# The .manifest marks Win10+ support; without it Chromium sees Windows 8.
add_executable(vitrine_helper WIN32 src/helper.cpp src/vitrine_helper.manifest)
SET_EXECUTABLE_TARGET_PROPERTIES(vitrine_helper)
target_include_directories(vitrine_helper PRIVATE ${CEF_ROOT})
target_link_libraries(vitrine_helper libcef_lib libcef_dll_wrapper ${CEF_STANDARD_LIBS})
add_dependencies(vitrine_helper libcef_dll_wrapper)

foreach(t vitrine vitrine_helper)
  set_target_properties(${t} PROPERTIES RUNTIME_OUTPUT_DIRECTORY_RELEASE "${OUT_DIR}")
endforeach()

COPY_FILES(vitrine "${CEF_BINARY_FILES}" "${CEF_BINARY_DIR_RELEASE}" "${OUT_DIR}")
COPY_FILES(vitrine "${CEF_RESOURCE_FILES}" "${CEF_RESOURCE_DIR}" "${OUT_DIR}")
