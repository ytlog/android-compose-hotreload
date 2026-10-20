cmake_minimum_required(VERSION 3.22.1)
project(compose_hot_reload_android LANGUAGES CXX)

# 'jvmti.h' is copied from the build JDK by Gradle (the NDK does not ship it)
if (NOT DEFINED JVMTI_INCLUDE_DIR)
    message(FATAL_ERROR "JVMTI_INCLUDE_DIR is not set")
endif ()

add_library(compose_hot_reload_android SHARED agent.cc)
target_compile_features(compose_hot_reload_android PRIVATE cxx_std_17)
target_compile_options(compose_hot_reload_android PRIVATE -Wall -Wextra -Werror)
target_include_directories(compose_hot_reload_android PRIVATE ${JVMTI_INCLUDE_DIR})
target_link_options(compose_hot_reload_android PRIVATE "LINKER:-z,max-page-size=16384")
