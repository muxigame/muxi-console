/* Original DS display/input witness for the independent ARM7 wireless challenge, MIT. */
typedef unsigned short u16;typedef unsigned int u32;
#define R16(a) (*(volatile u16*)(a))
#define R32(a) (*(volatile u32*)(a))
#ifndef ROLE
#define ROLE 0
#endif
void main(void){
 R16(0x04000304)=0x820f;*(volatile unsigned char*)0x04000240=0x80;R32(0x04000000)=0x00020000;R32(0x04001000)=0x00010000;
 volatile u16* image=(volatile u16*)0x06800000;volatile u32* shared=(volatile u32*)0x023fc000;
 for(unsigned i=0;i<256*192;i++)image[i]=0x801f;
 unsigned counter=0,released=1;
 while(1){
  while(R16(0x04000006)>=192){}while(R16(0x04000006)<192){}
  u16 color=(R16(0x04000130)&1)?0x801f:0x83e0;
  if(color==0x83e0){if(released)counter++;released=0;}else released=1;shared[12]=counter;
  for(unsigned y=48;y<112;y++)for(unsigned x=96;x<160;x++)image[y*256+x]=color;
  u16 received=shared[2]==(ROLE?0x3c:0xa7)?0x83e0:0x801f;
  for(unsigned y=8;y<24;y++)for(unsigned x=8;x<24;x++)image[y*256+x]=received;
  for(unsigned bit=0;bit<16;bit++){u16 value=(shared[11]&(1u<<bit))?0x83e0:0x801f;for(unsigned y=8;y<24;y++)for(unsigned x=32+bit*8;x<40+bit*8;x++)image[y*256+x]=value;}
  for(unsigned bit=0;bit<8;bit++){u16 value=(counter&(1u<<bit))?0x83e0:0x801f;for(unsigned y=32;y<40;y++)for(unsigned x=32+bit*8;x<40+bit*8;x++)image[y*256+x]=value;}
 }
}
