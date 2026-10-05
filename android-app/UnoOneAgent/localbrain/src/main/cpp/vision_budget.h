#pragma once
#include <cstddef>
#include <cmath>
#include <algorithm>
#include <utility>
namespace vision_budget {
constexpr std::size_t maxEncodedBytes = 4 * 1024 * 1024;
constexpr bool encoded(std::size_t size) { return size > 0 && size <= maxEncodedBytes; }
// Input decode bound; encoder upsampling may exceed 768 on a thin axis.
// Its independently checked total pixel budget remains bounded.
constexpr bool dimensions(int width, int height) {
    return width >= 32 && height >= 32 && width <= 768 && height <= 768 &&
        static_cast<long long>(width) * height <= 589824;
}
// Mirrors pinned MNN 024a946 omni.cpp qwenVlSmartResize, including ties-to-even.
constexpr int maxEncoderPixels = 589824;
inline std::pair<int,int> resized(int width, int height, int factor, int minPixels) {
    if (!dimensions(width,height) || (factor != 28 && factor != 32) ||
        minPixels <= 0 || minPixels > maxEncoderPixels) return {0,0};
    auto aligned = [factor](int n) {
        float v = static_cast<float>(n) / factor;
        float f = std::floor(v), diff = v-f;
        int r = static_cast<int>(f);
        if (diff > 0.5f+1e-6f || (!(diff < 0.5f-1e-6f) && r%2)) ++r;
        return r*factor;
    };
    int w=aligned(width), h=aligned(height);
    if (static_cast<long long>(w)*h > maxEncoderPixels) {
        double beta=std::sqrt(static_cast<double>(height)*width/maxEncoderPixels);
        h=std::max(factor,static_cast<int>(std::floor(height/beta/factor))*factor);
        w=std::max(factor,static_cast<int>(std::floor(width/beta/factor))*factor);
    } else if (static_cast<long long>(w)*h < minPixels) {
        double beta=std::sqrt(static_cast<double>(minPixels)/(static_cast<double>(height)*width));
        h=static_cast<int>(std::ceil(height*beta/factor))*factor;
        w=static_cast<int>(std::ceil(width*beta/factor))*factor;
    }
    return {w,h};
}
inline bool encoder(int width, int height, int factor, int minPixels) {
    auto size=resized(width,height,factor,minPixels);
    return size.first > 0 && size.second > 0 &&
        static_cast<long long>(size.first)*size.second <= maxEncoderPixels;
}
constexpr bool tokens(std::size_t prompt, int output, int context) {
    return prompt > 0 && output > 0 && context > output &&
        prompt <= static_cast<std::size_t>(context - output);
}
}
