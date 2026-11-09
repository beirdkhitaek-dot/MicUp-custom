cmake_minimum_required(VERSION 3.22)
project(null_commander LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# CLAP headers. Pass -DCLAP_INCLUDE_DIR=/path/to/clap/include to build offline.
if(NOT CLAP_INCLUDE_DIR)
    include(FetchContent)
    FetchContent_Declare(clap
        GIT_REPOSITORY https://github.com/free-audio/clap.git
        GIT_TAG        1.2.1
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(clap)
    set(CLAP_INCLUDE_DIR ${clap_SOURCE_DIR}/include)
endif()

add_library(null_commander MODULE null_commander.cpp)
target_include_directories(null_commander PRIVATE ${CLAP_INCLUDE_DIR})
target_compile_options(null_commander PRIVATE -O2 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
set_target_properties(null_commander PROPERTIES PREFIX "" OUTPUT_NAME "NullCommander" SUFFIX ".clap")  # .clap so MicUp imports it as CLAP (a plain .so is guessed to be VST3)
# On Android, configure with -DANDROID_STL=c++_static so the plugin carries its own C++ runtime
# (it is dlopen()ed into the app and must not depend on the app's libc++_shared.so).
