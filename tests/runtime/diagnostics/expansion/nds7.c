/* Original Muxi DS secondary CPU: report the real emulated touchscreen pen line. */
static unsigned adc(unsigned command){
 volatile unsigned short*control=(volatile unsigned short*)0x040001c0;volatile unsigned char*data=(volatile unsigned char*)0x040001c2;
 while(*control&0x80){}*control=0x8a00;*data=command;while(*control&0x80){}*data=0;while(*control&0x80){}unsigned high=*data;*control=0x8200;*data=0;while(*control&0x80){}return ((high&127)<<5)|(*data>>3);
}
void main(void){
 volatile unsigned short*key=(volatile unsigned short*)0x04000136;
 volatile unsigned int*shared=(volatile unsigned int*)0x023ff000;
 while(1){shared[0]=(*key&0x40)?0:1;shared[1]=adc(0xd0)>>4;shared[2]=adc(0x90)>>4;unsigned value=adc(0xe0);shared[3]=value>2048?value-2048:2048-value;for(volatile unsigned delay=0;delay<800;delay++){} }
}
