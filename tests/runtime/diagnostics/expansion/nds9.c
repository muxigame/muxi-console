/* Original Muxi bare-metal DS diagnostic, MIT. No firmware or game payload. */
typedef unsigned short u16;typedef unsigned int u32;
#define REG16(a) (*(volatile u16*)(a))
#define REG32(a) (*(volatile u32*)(a))
void main(void){
 REG16(0x04000304)=0x820f;*(volatile unsigned char*)0x04000240=0x80;
 REG32(0x04000000)=0x00020000;REG32(0x04001000)=0x00010000;
 volatile u16*image=(volatile u16*)0x06800000;unsigned frame=0;
 while(1){
  while(REG16(0x04000006)>=192){}while(REG16(0x04000006)<192){}
  u16 color=(REG16(0x04000130)&1)?0x801f:0x83e0;
  for(unsigned i=0;i<256*192;i++)image[i]=color;
  REG16(0x05000400)=REG32(0x023ff000)?0x03ff:0x7c00;
  unsigned tx=REG32(0x023ff004),ty=REG32(0x023ff008),mic=REG32(0x023ff00c);
  if(REG32(0x023ff000)&&tx<256&&ty<192)image[ty*256+tx]=0xffff;
  if(mic>255)mic=255;for(unsigned x=0;x<mic;x++)image[256*16+x]=0xffff;
  image[(frame++%256)+256*8]=0xffff;
 }
}
