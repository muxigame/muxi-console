/* Original bare-metal DS wireless challenge, MIT. Register and firmware layouts:
 * melonDS 16f127db Wifi.h / SPI_Firmware.h and the public DS hardware interface.
 * Calibration and MAC are read from the running core's generated firmware, never bundled BIOS bytes. */
typedef unsigned char u8;typedef unsigned short u16;typedef unsigned int u32;
#define R16(a) (*(volatile u16*)(a))
#define W(o) R16(0x04800000+(o))
#ifndef ROLE
#define ROLE 0
#endif
static u8 firmware[512];
static u8 spi(u8 value){while(R16(0x040001c0)&0x80){}R16(0x040001c2)=value;while(R16(0x040001c0)&0x80){}return R16(0x040001c2);}
static void load_firmware(void){R16(0x040001c0)=0x8900;spi(3);spi(0);spi(0);spi(0);for(unsigned i=0;i<512;i++){if(i==511)R16(0x040001c0)=0x8100;firmware[i]=spi(0);}R16(0x040001c0)=0;}
static void rf(u8 index,u8 value){W(0x17e)=((u16)index<<8)|value;W(0x17c)=5;}
void main(void){
 volatile u32* shared=(volatile u32*)0x023fc000;volatile u16* tx=(volatile u16*)0x04804000;volatile u16* cmd=(volatile u16*)0x04804100;volatile u16* rx=(volatile u16*)0x04804800;
 shared[0]=shared[1]=shared[2]=shared[3]=0;load_firmware();shared[4]=firmware[0x40];
 R16(0x04000304)|=2;W(0x36)=0;W(0x04)=0xc000;W(0x04)=1;W(0x06)=3;W(0x40)=0x8000;
 if(firmware[0x40]!=3){shared[3]=0xe003;while(1){}}
 for(unsigned i=0;i<3;i++)W(0x18+i*2)=firmware[0x36+i*2]|((u16)firmware[0x37+i*2]<<8);
 W(0x20)=0x4d02;W(0x22)=0x4c58;W(0x24)=0x4b4e;
 /* RF type 3 channel one, using only this firmware's own calibration bytes. */
 rf(firmware[0x116],firmware[0x117]);rf(firmware[0x125],firmware[0x126]);
 W(0x50)=0x4800;W(0x52)=0x5f00;W(0x56)=0x2400;W(0x58)=0x4800;W(0x5a)=0x2400;W(0xd0)=0xffff;W(0xe0)=0xffff;W(0xe8)=1;W(0x30)=0x8001;
 for(unsigned i=0;i<24;i++)tx[i]=0;tx[4]=0x14;tx[5]=36;tx[6]=0x0008;
 tx[8]=tx[9]=tx[10]=0xffff;for(unsigned i=0;i<3;i++)tx[11+i]=W(0x18+i*2);tx[14]=0x4d02;tx[15]=0x4c58;tx[16]=0x4b4e;
 tx[18]=0x584d;tx[19]=0x5744;tx[20]=ROLE?0xa7:0x3c;unsigned frame=0;
 /* A real MAC CMD slot establishes the frontend's transport host ID. Its
    zero reply mask makes this an original probe, without a Nintendo protocol payload. */
 for(unsigned i=0;i<24;i++)cmd[i]=tx[i];cmd[19]=0;
 while(1){
  while(R16(0x04000006)>=192){}while(R16(0x04000006)<192){}
  if((frame++&7)==(ROLE?4:0)){tx[21]=shared[0];W(0xa0)=0x8000;W(0xae)=1;shared[0]++;}
  if(!ROLE&&(frame&15)==2){W(0x118)=0xffff;W(0x90)=0x8080;W(0xae)=2;}
  for(unsigned i=0;i<0xb70;i++)if(rx[i]==0x584d&&rx[i+1]==0x5744&&rx[i+2]==(ROLE?0x3c:0xa7)){shared[1]++;shared[2]=rx[i+2];if(rx[i+3]>shared[11])shared[11]=rx[i+3];}
  shared[5]=W(0x214);shared[6]=W(0x3c);shared[7]=W(0xb6);shared[8]=W(0x10);shared[9]=W(0x54);
 }
}
