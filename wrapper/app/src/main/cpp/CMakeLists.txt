cmake_minimum_required(VERSION 3.22.1)
project(wrapper LANGUAGES CXX)

add_library(wrapper SHARED
        wrapper_jni.cpp
        guest_native.cpp)

target_compile_options(wrapper PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_options(wrapper PRIVATE -Wl,--no-undefined)

# Roblox's engine is loaded at runtime from the verified archive, never linked
# here: the wrapper ships no Roblox code.
find_library(log-lib log)
target_link_libraries(wrapper ${log-lib} dl)
