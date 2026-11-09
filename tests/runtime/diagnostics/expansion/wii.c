/* Original Muxi Wii homebrew diagnostic, MIT. SDK libraries are separately licensed. */
#include <gccore.h>
#include <wiiuse/wpad.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ogc/isfs.h>
static void bar(u32*p,int stride,int x,int y,int width,int height,u32 color){
 if(width<0)width=0;if(width>144)width=144;
 for(int row=y;row<y+height;row++)for(int col=x;col<x+width;col+=2)p[row*stride+col/2]=color;
}
int main(int argc,char**argv){
 VIDEO_Init();WPAD_Init();PAD_Init();GXRModeObj*mode=VIDEO_GetPreferredMode(NULL);void*framebuffer=MEM_K0_TO_K1(SYS_AllocateFramebuffer(mode));
 VIDEO_Configure(mode);VIDEO_SetNextFramebuffer(framebuffer);VIDEO_SetBlack(FALSE);VIDEO_Flush();VIDEO_WaitVSync();
 for(int p=0;p<4;p++){WPAD_SetDataFormat(p,WPAD_FMT_BTNS_ACC_IR);WPAD_SetVRes(p,640,480);}
 ISFS_Initialize();unsigned frame=0;int configured[4]={0};int saved=0;
 while(1){
  WPAD_ScanPads();PAD_ScanPads();u32* pixels=framebuffer;u32 held=WPAD_ButtonsHeld(0);u32 color=held&WPAD_BUTTON_A?0x91229136:0x515A51F0;
  for(unsigned i=0;i<(unsigned)mode->fbWidth*mode->xfbHeight/2;i++)pixels[i]=color;
  ++frame;
  for(int p=0;p<4;p++){
   ir_t ir={0};gforce_t g={0};u32 type=0;s32 connected=WPAD_Probe(p,&type);
   if(connected==0&&!configured[p]){WPAD_SetDataFormat(p,WPAD_FMT_BTNS_ACC_IR);WPAD_SetVRes(p,640,480);configured[p]=1;}
   WPAD_IR(p,&ir);WPAD_GForce(p,&g);int x=8+p*160;int stride=mode->fbWidth/2;
   bar(pixels,stride,x,24,144,12,connected==0?0x91229136:0x515A51F0);
   bar(pixels,stride,x,48,WPAD_ButtonsHeld(p)&WPAD_BUTTON_A?144:8,20,0x91229136);
   bar(pixels,stride,x,88,ir.valid?(int)(ir.x*144/640):8,16,0xE180E180);
   bar(pixels,stride,x,112,ir.valid?(int)(ir.y*144/480):8,16,0xE180E180);
   bar(pixels,stride,x,160,72+(int)(g.x*32),16,0x515A51F0);
   bar(pixels,stride,x,192,72+(int)(g.y*32),16,0x91229136);
   bar(pixels,stride,x,224,72+(int)(g.z*32),16,0x29F0296E);
   if(p==0&&!(WPAD_ButtonsHeld(0)&WPAD_BUTTON_A))saved=0;
   if(p==0&&!saved&&frame>=150&&(WPAD_ButtonsHeld(0)&WPAD_BUTTON_A)){
    const char*name="/shared2/muxi-console-original-save.dat";ISFS_CreateFile(name,0,3,3,3);s32 fd=ISFS_Open(name,ISFS_OPEN_RW);
    static unsigned record[16] __attribute__((aligned(32)));
    record[0]=0x4d585749;record[1]=2;record[2]=frame;record[3]=WPAD_ButtonsHeld(0);record[4]=connected;record[5]=type;record[6]=ir.valid;
    record[7]=(int)(ir.x*100);record[8]=(int)(ir.y*100);record[9]=(int)(g.x*1000);record[10]=(int)(g.y*1000);record[11]=(int)(g.z*1000);
    if(fd>=0){saved=ISFS_Write(fd,record,sizeof(record))==sizeof(record);ISFS_Close(fd);}
   }
  }
  VIDEO_Flush();VIDEO_WaitVSync();
 }
 return 0;
}
