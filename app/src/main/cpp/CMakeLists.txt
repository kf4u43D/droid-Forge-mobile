cmake_minimum_required(VERSION 3.22)
project(AndroForgeAndroid LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
get_filename_component(ANDROFORGE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_subdirectory("${ANDROFORGE_ROOT}/core" "${CMAKE_BINARY_DIR}/core")
add_library(androforge SHARED bridge.cpp)
target_link_libraries(androforge PRIVATE androforge_core)
target_compile_options(androforge PRIVATE -Wall -Wextra -Wpedantic)
target_link_options(androforge PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
    "-Wl,-z,relro,-z,now")
if(CMAKE_BUILD_TYPE STREQUAL "Release" AND CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
    # CMake 3.22's generic IPO rule selects gold. Use the NDK's LLD instead.
    target_compile_options(androforge PRIVATE -flto=thin)
    target_compile_options(androforge_core PRIVATE -flto=thin)
    target_link_options(androforge PRIVATE -flto=thin -fuse-ld=lld)
endif()
