#pragma once
#include "vision_budget.h"
// MNN imgcodecs uses this exact pinned header with STB_IMAGE_STATIC: its info
// symbol is not exported. Compile a private PNG/JPEG-only copy; info allocates
// no decoded raster. BMP and all other formats are deliberately not admitted.
#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_STATIC
#define STBI_NO_THREAD_LOCALS
#define STBI_NO_STDIO
#define STBI_ONLY_PNG
#define STBI_ONLY_JPEG
#include <imageHelper/stb_image.h>
namespace image_admission {
inline bool inspect(const unsigned char* data, std::size_t size, int& width, int& height) {
    if (!vision_budget::encoded(size)) return false;
    const bool png = size >= 8 && data[0] == 137 && data[1] == 80 && data[2] == 78 && data[3] == 71 && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10;
    const bool jpeg = size >= 3 && data[0] == 255 && data[1] == 216 && data[2] == 255;
    int channels = 0;
    return (png || jpeg) && stbi_info_from_memory(data, static_cast<int>(size), &width, &height, &channels) && vision_budget::dimensions(width, height);
}
}
