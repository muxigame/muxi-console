package net.muxigame.console.mc;

import net.muxigame.console.domain.DomainException;

/** Keep storage/ABI diagnostics separate from the player's short actionable message. */
public final class UserErrors {
    public static String describe(Exception error){
        String text=error.getMessage();if(text!=null&&text.codePoints().anyMatch(c->c>=0x3400&&c<=0x9fff))return text;
        String code=error instanceof DomainException d?d.code():"io";
        return switch(code){
            case "rights_required"->"请先确认拥有本次上传及卡带使用所需的授权";
            case "file","filename"->"请选择可读取的游戏文件";
            case "platform","extension"->"无法确定机型，请选择与文件对应的平台";
            case "format"->"文件格式与所选平台不符，或尚未支持";
            case "firmware"->"固件不能作为游戏卡带上传";
            case "size","chunk"->"文件大小超出当前平台或上传限制";
            case "hash"->"文件校验失败，请重新选择文件再上传";
            case "offset","incomplete"->"文件尚未完整传输，请重新上传";
            case "quota","save_quota"->"服务器存储空间不足，请联系服主";
            case "limit"->"正在处理其他上传，请稍后重试";
            case "expired","upload","save_expired","save_session"->"传输会话已结束，请重新操作";
            case "permission","controller","occupied"->"当前控制权限已改变，请重新选择控制席";
            default->"操作未能完成，请重试或联系服主";
        };
    }
    private UserErrors(){}
}
