#pragma once
#include <cstdio>
#include <cstdarg>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
inline int __android_log_print(int, const char* tag, const char* fmt, ...) { va_list a; va_start(a, fmt); printf("[%s] ", tag); vprintf(fmt, a); printf("\n"); va_end(a); return 0; }
