cmake_minimum_required(VERSION 3.22.1)
project(rotg02native LANGUAGES CXX)

add_library(rotg02native SHARED
    rotg02_native.cpp
)

target_compile_features(rotg02native PRIVATE cxx_std_17)
target_compile_options(rotg02native PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
)
