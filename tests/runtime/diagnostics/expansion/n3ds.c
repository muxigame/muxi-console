/* Original Muxi homebrew diagnostic, MIT. Uses public libctru APIs; no game/BIOS assets. */
#include <3ds.h>
#include <stdio.h>
#include <stdlib.h>
#include <malloc.h>
#include <string.h>
static void fill(gfxScreen_t screen,unsigned char red,unsigned char green,unsigned char blue){u16 w,h;u8* p=gfxGetFramebuffer(screen,GFX_LEFT,&w,&h);for(unsigned i=0;i<(unsigned)w*h;i++){p[3*i]=blue;p[3*i+1]=green;p[3*i+2]=red;}}
static void bar(unsigned y,unsigned length){if(length>200)length=200;u16 w,h;u8*p=gfxGetFramebuffer(GFX_TOP,GFX_LEFT,&w,&h);for(unsigned x=0;x<length;x++)for(unsigned row=y;row<y+3;row++){unsigned i=(x*240+239-row)*3;p[i]=p[i+1]=p[i+2]=255;}}
int main(void){
 gfxInitDefault();HIDUSER_EnableAccelerometer();HIDUSER_EnableGyroscope();archiveMount(ARCHIVE_SDMC,fsMakePath(PATH_EMPTY,""),"sdmc");
 unsigned char* mic=memalign(0x1000,0x10000);Result mr=mic?micInit(mic,0x10000):-1;
 if(R_SUCCEEDED(mr))MICU_StartSampling(MICU_ENCODING_PCM16_SIGNED,MICU_SAMPLE_RATE_32730,0,micGetSampleDataSize(),true);
 unsigned frame=0;int saved=0;
 while(aptMainLoop()){
  hidScanInput();u32 held=hidKeysHeld(),down=hidKeysDown();touchPosition touch;hidTouchRead(&touch);accelVector accel;hidAccelRead(&accel);angularRate gyro;hidGyroRead(&gyro);
  fill(GFX_TOP,held&KEY_A?0:240,held&KEY_A?240:0,frame&1?24:16);
  unsigned energy=0;if(R_SUCCEEDED(mr)){unsigned length=micGetSampleDataSize(),end=micGetLastSampleOffset()&~1u;for(unsigned s=0;s<128;s++){unsigned at=(end+length-2-2*s)%length;short sample;memcpy(&sample,mic+at,2);unsigned amplitude=sample<0?-sample:sample;if(amplitude>energy)energy=amplitude;}}
  bar(4,(unsigned)abs(accel.x)/4);bar(9,(unsigned)abs(gyro.x)/16);bar(14,energy/128);
  fill(GFX_BOTTOM,held&KEY_TOUCH?240:0,held&KEY_TOUCH?220:0,held&KEY_TOUCH?0:240);
  if(held&KEY_TOUCH){u16 w,h;u8*p=gfxGetFramebuffer(GFX_BOTTOM,GFX_LEFT,&w,&h);unsigned x=touch.px<320?touch.px:319,y=touch.py<240?touch.py:239;unsigned i=(x*240+(239-y))*3;p[i]=p[i+1]=p[i+2]=255;}
  if(down&KEY_A){FILE*f=fopen("sdmc:/muxi-console-original-save.dat","wb");if(f){unsigned record[6]={0x4d584433,++saved,frame,(unsigned)accel.x,(unsigned)gyro.x,energy};fwrite(record,1,sizeof(record),f);fclose(f);}}
  gfxFlushBuffers();gfxSwapBuffers();gspWaitForVBlank();frame++;
 }
 if(R_SUCCEEDED(mr)){MICU_StopSampling();micExit();}free(mic);HIDUSER_DisableGyroscope();HIDUSER_DisableAccelerometer();gfxExit();return 0;
}
