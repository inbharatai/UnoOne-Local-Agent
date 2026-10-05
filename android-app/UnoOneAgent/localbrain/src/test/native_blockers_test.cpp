#include "../main/cpp/request_epoch.h"
#include "../main/cpp/image_admission.h"
#include <cassert>
#include <future>
#include <thread>
#include <vector>
#include <iostream>
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STB_IMAGE_WRITE_STATIC
#include <imageHelper/stb_image_write.h>
static std::vector<unsigned char> png(unsigned w, unsigned h) {
    std::vector<unsigned char> b={137,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82};
    for (auto value : {w,h}) for(int shift : {24,16,8,0}) b.push_back((value>>shift)&255);
    b.insert(b.end(), {8,2,0,0,0,0,0,0,0,0,0,0,0,73,68,65,84}); return b; // Header-only fixture, not a full decoder test.
}
int main() {
    RequestEpoch state;
    const auto old = state.admit();
    std::promise<void> entered, resume;
    auto resumed=resume.get_future();
    bool prepared=true;
    std::thread request([&]{ entered.set_value(); resumed.wait(); prepared=state.prepare(old); });
    entered.get_future().wait(); state.cancel(); resume.set_value(); request.join();
    assert(!prepared && state.cancelled.load() && !state.begin(old));
    const auto fresh=state.admit(); assert(state.prepare(fresh));
    state.cancel(); assert(!state.begin(fresh)); // Stop between prepare and JNI generate
    const auto newest=state.admit(); assert(state.prepare(newest) && state.begin(newest));
    assert(!state.prepare(old) && !state.prepare(fresh)); // never freshen old tokens
    state.cancel(); assert(state.cancelled.load()); // encoder/token polling latch
    int w=0,h=0;
    std::vector<unsigned char> raster(768*768*3, 0), validPng;
    assert(stbi_write_png_to_func([](void* out,void* bytes,int n) {
        auto& b=*static_cast<std::vector<unsigned char>*>(out);
        auto* p=static_cast<unsigned char*>(bytes); b.insert(b.end(),p,p+n);
    }, &validPng,768,768,3,raster.data(),768*3));
    assert(image_admission::inspect(validPng.data(),validPng.size(),w,h) && w==768 && h==768);
    auto maximum=png(768,768); assert(image_admission::inspect(maximum.data(),maximum.size(),w,h) && w==768 && h==768);
    auto huge=png(100000,100000); assert(!image_admission::inspect(huge.data(),huge.size(),w,h));
    auto narrow=png(31,768); assert(!image_admission::inspect(narrow.data(),narrow.size(),w,h));
    for (std::size_t n=0;n<24;++n) assert(!image_admission::inspect(maximum.data(),n,w,h));
    unsigned char bmp[]={66,77,0,0,0,0,0,0}; assert(!image_admission::inspect(bmp,sizeof(bmp),w,h));
    unsigned char jpeg[]={255,216,255,192,0,17,8,3,0,3,0,3,1,17,0,2,17,0,3,17,0};
    assert(image_admission::inspect(jpeg,sizeof(jpeg),w,h) && w==768 && h==768);
    jpeg[7]=255; assert(!image_admission::inspect(jpeg,sizeof(jpeg),w,h));
    assert(!image_admission::inspect(maximum.data(),4194305,w,h));
    std::cout << "PASS native epoch barrier, prepare/entry race, fresh/stale epochs; PNG/JPEG header bounds and unsupported/truncated input\n";
}
