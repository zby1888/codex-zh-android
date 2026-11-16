cmake_minimum_required(VERSION 3.22.1)
project(codex_terminal LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
    libvterm
    GIT_REPOSITORY https://github.com/neovim/libvterm.git
    GIT_TAG 934bc2fbf21800ac3458a499df8820ca5fb45fd3
    GIT_SHALLOW FALSE
)
FetchContent_GetProperties(libvterm)
if(NOT libvterm_POPULATED)
    FetchContent_Populate(libvterm)
endif()

add_library(vterm STATIC
    ${libvterm_SOURCE_DIR}/src/encoding.c
    ${libvterm_SOURCE_DIR}/src/keyboard.c
    ${libvterm_SOURCE_DIR}/src/mouse.c
    ${libvterm_SOURCE_DIR}/src/parser.c
    ${libvterm_SOURCE_DIR}/src/pen.c
    ${libvterm_SOURCE_DIR}/src/screen.c
    ${libvterm_SOURCE_DIR}/src/state.c
    ${libvterm_SOURCE_DIR}/src/unicode.c
    ${libvterm_SOURCE_DIR}/src/vterm.c
)
target_include_directories(vterm PUBLIC ${libvterm_SOURCE_DIR}/include PRIVATE ${libvterm_SOURCE_DIR}/src)
target_compile_options(vterm PRIVATE -Wall -Wextra -Wno-unused-parameter)

add_library(codex_terminal SHARED native_pty_terminal.cpp)
target_include_directories(codex_terminal PRIVATE ${libvterm_SOURCE_DIR}/include)
target_compile_options(codex_terminal PRIVATE -Wall -Wextra)
target_link_libraries(codex_terminal PRIVATE vterm log)
