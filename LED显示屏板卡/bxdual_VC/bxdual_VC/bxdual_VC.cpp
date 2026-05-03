// bxdual_VC.cpp : 定义控制台应用程序的入口点。
//

#include "stdafx.h"
#include <iostream>
#include "windows.h"
using namespace std;
#include "../include/bx_dual_sdk.h"

#pragma comment(lib, "bx_sdk_dual.lib")

void addProgram_G5();
void addArea_G5(Ouint16 AreaID,Ouint8 AreaType,Ouint16 AreaX,Ouint16 AreaY,Ouint16 AreaWidth,Ouint16 AreaHeight);
void addAreaTime_G5(Ouint16 AreaID);
void addAreaPicture_G5(Ouint16 AreaID,Ouint8 str[]);
void addAreaPicturePic_G5(Ouint16 AreaID);
void tcp_send_program_G5(Ouint8* ip, Ouint16 port);
void addAreaPicturePic_G5(Ouint16 areaID);

void addProgram_G6();
void addArea_G6(Ouint16 AreaID,Ouint8 AreaType,Ouint16 AreaX,Ouint16 AreaY,Ouint16 AreaWidth,Ouint16 AreaHeight);
void addAreaTime_G6(Ouint16 AreaID);
void addAreaPicture_G6(Ouint16 AreaID,Ouint8 str[]);
void addAreaPicturePic_G6(Ouint16 AreaID);
void tcp_send_program_G6(Ouint8* ip, Ouint16 port);
void dynamic_6(Ouint8* ip,BYTE cmb_ping_Color);
void com_send_program_G5(Oint8* uartPort, Ouint8 baudRate);
void onbonTest_bxDual_dynamicArea_AddAreaWithTxt_5G(void);

void onbonTest_Dynamic_6G_Pic();
int _tmain()
{
	unsigned char ip[] = "192.168.89.20";
	unsigned short port = 5005;
    //串口号 "COM1",大于9以上做特殊处理，如"\\\\.\\COM17"
	Oint8 COMPort[] = "\\\\.\\COM17";
	int ret = 0;
	ret = bxDual_InitSdk();//初始化动态库
	Ouint16 c_type = 0;
	Ping_data retdata;
	ret = bxDual_cmd_tcpPing(ip,port,&retdata);
	////bxDual_cmd_uart_searchController(&retdata, COMPort);
	//if(ret != 0){
	//	printf("bxDual_cmd_udpPing run error...\n");
	//}else{
	//	printf("bxDual_cmd_udpPing run succeed...\n");
	//	//memset((void*)ip, 0, sizeof(ip));
	//	//memcpy((void*)ip, (void*)retdata.ipAdder, strlen((char*)retdata.ipAdder));
	//	printf("retdata.ipAdder =====%s \n", retdata.ipAdder);
	//	printf("retdata.ControllerType == 0x%x \n", retdata.ControllerType);
	//	c_type = retdata.ControllerType;
	//}
	//printf("ret =====bxDual_cmd_udpPing===== %d \n", ret);
 //   ret = bxDual_cmd_udpSetIP(2, (Ouint8*)"192.168.89.123", (Ouint8*)"255.255.255.0", (Ouint8*)"192.168.89.100",5005, 0, (Ouint8*)"127.0.0.1", 5005, (Ouint8*)"00000000", 20, (Ouint8*)"BX-NET000001");
	//printf("ret =====bxDual_cmd_udpSetIP===== %d \n", ret);
	
	//查询设备显示节目
	//NetSearchCmdRet oNetSearchRet;
	//memset(&oNetSearchRet, 0, sizeof(oNetSearchRet));
	//int SearchRes = bxDual_cmd_tcpNetworkSearch_6G(ip, port, &oNetSearchRet);
	//		if (SearchRes == 0) //或者状态成功
	//		{
	//			printf( "当前显示节目 =====%s \n", oNetSearchRet.CurrentProgram);
	//		}
	//		else //获取状态失败
	//		{
	//			printf("error:%d \n", SearchRes);
	//		}

    BYTE cmb_ping_Color = 1;
    if (retdata.Color == 1) { cmb_ping_Color = 1; }
    else if (retdata.Color == 3) { cmb_ping_Color = 2; }
    else if (retdata.Color == 7) { cmb_ping_Color = 3; }
    else { cmb_ping_Color = 4; }
	
    ret = bxDual_program_setScreenParams_G56((E_ScreenColor_G56)cmb_ping_Color, retdata.ControllerType, eDOUBLE_COLOR_PIXTYPE_1);
	addProgram_G6();
	addArea_G6(0,0,0,0,64,32);
	addAreaPicture_G6(0,(Ouint8*)"132");
	tcp_send_program_G6(ip,5005);

	bxDual_ReleaseSdk();
    system("pause");
	//return 0;
}

//添加节目
void addProgram_G5()
{
	EQprogramHeader header;
	header.FileType=0x00;
	header.ProgramID = 0;
	header.ProgramStyle=0x00;
	header.ProgramPriority=0x00;
	header.ProgramPlayTimes=1;
	header.ProgramTimeSpan=0;
	header.ProgramWeek=0xff;
	header.ProgramLifeSpan_sy=0xffff;
	header.ProgramLifeSpan_sm=0x03;
	header.ProgramLifeSpan_sd=0x05;
	header.ProgramLifeSpan_ey=0xffff;
	header.ProgramLifeSpan_em=0x04;
	header.ProgramLifeSpan_ed=0x12;
	bxDual_program_addProgram(&header);
}
void addProgram_G6()
{
	EQprogramHeader_G6 pHeader;
	pHeader.FileType = 0x00;
	pHeader.ProgramID = 0;
	pHeader.ProgramStyle=0x00;
	pHeader.ProgramPriority=0x00;
	pHeader.ProgramPlayTimes=1;
	pHeader.ProgramTimeSpan=0;
	pHeader.SpecialFlag = 0;
	pHeader.CommExtendParaLen = 0x00;
	pHeader.ScheduNum = 0;
	pHeader.LoopValue = 0;
	pHeader.Intergrate = 0x00;
	pHeader.TimeAttributeNum = 0x00;
	pHeader.TimeAttribute0Offset = 0x0000;
	pHeader.ProgramWeek=0xff;
	pHeader.ProgramLifeSpan_sy=0xffff;
	pHeader.ProgramLifeSpan_sm=0x03;
	pHeader.ProgramLifeSpan_sd=0x14;
	pHeader.ProgramLifeSpan_ey=0xffff;
	pHeader.ProgramLifeSpan_em=0x03;
	pHeader.ProgramLifeSpan_ed=0x14;
	//pHeader.PlayPeriodGrpNum=0;

	bxDual_program_addProgram_G6(&pHeader);
}
//添加区域
void addArea_G5(Ouint16 AreaID,Ouint8 AreaType,Ouint16 AreaX,Ouint16 AreaY,Ouint16 AreaWidth,Ouint16 AreaHeight)
{
	Ouint16 nAreaID = AreaID;
	EQareaHeader aheader;
	aheader.AreaType = AreaType;
	aheader.AreaX = AreaX;
	aheader.AreaY = AreaY;
	aheader.AreaWidth = AreaWidth;
	aheader.AreaHeight = AreaHeight;
	bxDual_program_AddArea(nAreaID, &aheader); 
}
void addArea_G6(Ouint16 AreaID,Ouint8 AreaType,Ouint16 AreaX,Ouint16 AreaY,Ouint16 AreaWidth,Ouint16 AreaHeight)
{
	Ouint16 nAreaID = AreaID;
	EQareaHeader_G6 aHeader1;
	aHeader1.AreaType = AreaType;
	aHeader1.AreaX = AreaX;
	aHeader1.AreaY = AreaY;
	aHeader1.AreaWidth = AreaWidth;
	aHeader1.AreaHeight = AreaHeight;
	aHeader1.BackGroundFlag = 0x00;
	aHeader1.Transparency = 101;
	aHeader1.AreaEqual = 0x00;
	bxDual_program_addArea_G6(nAreaID,&aHeader1);
}
//添加时间内容
void addAreaTime_G5(Ouint16 AreaID)
{
	EQtimeAreaData_G56 timeData2;
	timeData2.linestyle = eMULTILINE;
	timeData2.color = eRED;
	timeData2.fontName = (Ouint8*)malloc(40);
	strcpy((Oint8*)timeData2.fontName,"./allfonts/1.ttf");
	timeData2.fontSize = 9;
	timeData2.fontBold = 0;
	timeData2.fontItalic = 0;
	timeData2.fontUnderline = 0;
	timeData2.fontAlign = 1;  //0--左对齐，1-居中，2-右对齐
	timeData2.date_enable = true;
	timeData2.datestyle = (E_DateStyle)eYYYY_MM_DD_MINUS;
	timeData2.time_enable = true;
	timeData2.timestyle = (E_TimeStyle)eHH_MM_SS_COLON;
	timeData2.week_enable = true;
	timeData2.weekstyle = (E_WeekStyle)eMonday_CHS;
	bxDual_program_timeAreaAddContent(AreaID,&timeData2);
}
void addAreaTime_G6(Ouint16 AreaID)
{
	EQtimeAreaData_G56 timeData;
	timeData.linestyle = eMULTILINE;
	timeData.color = eGREEN;
	timeData.fontName = (Ouint8*)malloc(sizeof(Ouint8)*40);
	strcpy((Oint8*)timeData.fontName,"黑体");
	timeData.fontSize = 12;
	timeData.fontBold = 0;
	timeData.fontItalic = 0;
	timeData.fontUnderline = 0;
	timeData.fontAlign = 1;  //0--左对齐，1-居中，2-右对齐
	timeData.date_enable = true;
	timeData.datestyle = (E_DateStyle)eYYYY_MM_DD_CHS;//eMM_DD_CHS;// //eYYYY_MM_DD_VIRGURE;// 
	timeData.week_enable = false;
	timeData.weekstyle = (E_WeekStyle)eMonday_CHS;
	timeData.time_enable = true;
	timeData.timestyle = (E_TimeStyle)eHH_MM_SS_COLON;
	bxDual_program_timeAreaAddContent_G6(AreaID,&timeData);
}
//添加文本
void addAreaPicture_G5(Ouint16 AreaID,Ouint8 str[])
{;
	EQpageHeader pheader;
	pheader.PageStyle = 0x00;
	pheader.DisplayMode = 0x03;
	pheader.ClearMode = 0x01;
	pheader.Speed = 32;
	pheader.StayTime = 0;
	pheader.RepeatTime = 1;
	pheader.ValidLen = 0;
	pheader.arrMode = eSINGLELINE;
	pheader.fontSize = 10;
	pheader.color = eYELLOW;
	pheader.fontBold = false;
	pheader.fontItalic = false;
	pheader.tdirection = pNORMAL;
	pheader.txtSpace = 0; 
	bxDual_program_picturesAreaAddTxt(0, str,(Ouint8*)"宋体",&pheader);
}
void addAreaPicture_G6(Ouint16 AreaID,Ouint8 str[])
{
	Ouint8* str1 =(Ouint8*)"4545\n5656";
	EQpageHeader_G6 pheader1;
	pheader1.PageStyle = 0x00;
	pheader1.DisplayMode = 0x4;
	pheader1.ClearMode = 0x01;
	pheader1.Speed = 1;
	pheader1.StayTime = 0;
	pheader1.RepeatTime = 1;
	pheader1.ValidLen = 1;
	pheader1.CartoonFrameRate = 0x00;
	pheader1.BackNotValidFlag = 0x00;
	pheader1.arrMode = eSINGLELINE; //eMULTILINE;// 
	pheader1.fontSize = 10;
	pheader1.color = E_Color_G56::eRED;   // E_Color_G56
	pheader1.fontBold = false;
	pheader1.fontItalic = false;
	pheader1.tdirection = pNORMAL;
	pheader1.txtSpace = 0;
	pheader1.Valign = 0;
	pheader1.Halign = 0;
	
	bxDual_program_picturesAreaAddTxt_G6(AreaID,str1,(Ouint8*)"宋体",&pheader1);
	//program_fontPath_picturesAreaAddTxt_G6(0,str,(Ouint8*)"C:/Windows/Fonts/simsun.ttc",&pheader1);
}
//添加图片
void addAreaPicturePic_G5(Ouint16 areaID)
{
    EQpageHeader pheader;
    pheader.PageStyle = 0x00;
    pheader.DisplayMode = 0x01;
    pheader.ClearMode = 0x01;
    pheader.Speed = 30;
    pheader.StayTime = 0;
    pheader.RepeatTime = 1;
    pheader.ValidLen = 0;
    pheader.arrMode = E_arrMode::eSINGLELINE;
    pheader.fontSize = 12;
    pheader.color = E_Color_G56::eRED;
    pheader.fontBold = false;
    pheader.fontItalic = false;
    pheader.tdirection = E_txtDirection::pNORMAL;
    pheader.txtSpace = 0;
    pheader.Valign = 2;
    pheader.Halign = 2;
    int err = bxDual_program_pictureAreaAddPic(areaID, 1, &pheader, (Ouint8*)"E:\\VSproject\\bxdual_VC\\lib\\lib32\\1.png");
}
void addAreaPicturePic_G6(Ouint16 areaID)
{
    EQpageHeader_G6 pheader;
    pheader.PageStyle = 0x00;
    pheader.DisplayMode = 0x03;
    pheader.ClearMode = 0x01;
    pheader.Speed = 15;
    pheader.StayTime = 500;
    pheader.RepeatTime = 1;
    pheader.ValidLen = 0;
    pheader.CartoonFrameRate = 0x00;
    pheader.BackNotValidFlag = 0x00;
    pheader.arrMode = E_arrMode::eSINGLELINE;
    pheader.fontSize = 10;
    pheader.color = E_Color_G56::eRED;
    pheader.fontBold = 0;
    pheader.fontItalic = 0;
    pheader.tdirection = E_txtDirection::pNORMAL;
    pheader.txtSpace = 0;
    pheader.Valign = 2;
    pheader.Halign = 2;
    Ouint8* img = (Ouint8*)"F:/work github/Debug HUB/BX-V-VI/bx.dual.cplus/src/lib/2Led0_0.png";
    int err = bxDual_program_pictureAreaAddPic_G6(areaID, 0, &pheader, img);
}
//发送节目
void tcp_send_program_G5(Ouint8* ip, Ouint16 port)
{
	Oint8 ret;
	ret = bxDual_cmd_ofsStartFileTransf(ip, port);
	printf("tcp_send_program_G5L:cmd_ofsStartFileTransf===== %d \n", ret);
	if(ret != 0){
		printf("cmd_ofsStartFileTransf run error...");
	}else{
		printf("cmd_ofsStartFileTransf run succeed...");
	}

	EQprogram program;
	memset((void*)&program, 0, sizeof(program));
	bxDual_program_IntegrateProgramFile(&program);

	ret = bxDual_cmd_ofsWriteFile(ip, port, program.fileName, program.fileType, program.fileLen, 1, program.fileAddre);
	if(ret != 0){
		printf("cmd_ofsWriteFile run error...");
	}else{
		printf("cmd_ofsWriteFile run succeed...");
	}
	printf("tcp_send_program_G5:cmd_ofsWriteFile===== %d \n", ret);
	printf("fileName_G5 == %s \n", program.fileName);
	printf("fileType_G5 == %d \n", program.fileType);
	printf("fileLen_G5 == %d \n", program.fileLen);
	printf("fileCRC32_G5 == %d \n",program.fileCRC32);
	ret = bxDual_cmd_ofsEndFileTransf(ip, port);
	if(ret != 0){
		printf("cmd_ofsEndFileTransf run error...");
	}else{
		printf("cmd_ofsEndFileTransf run succeed...");
	}
	printf("tcp_send_program_G5:md_ofsWriteFile===== %d \n", ret);
	bxDual_program_deleteProgram();
	bxDual_program_freeBuffer(&program);
}
void com_send_program_G5(Oint8* uartPort, Ouint8 baudRate)
{
	EQprogram program;
	memset((void*)&program, 0, sizeof(program));
	bxDual_program_IntegrateProgramFile(&program);
	Oint8 ret;

	ret = bxDual_cmd_uart_ofsStartFileTransf(uartPort, baudRate);
	printf("tcp_send_program_G5L:cmd_ofsStartFileTransf===== %d \n", ret);
	if(ret != 0){
		printf("cmd_ofsStartFileTransf run error...");
	}else{
		printf("cmd_ofsStartFileTransf run succeed...");
	}


	ret = bxDual_cmd_uart_ofsWriteFile(uartPort, baudRate, program.fileName, program.fileType, program.fileLen, 1, program.fileAddre);
	if(ret != 0){
		printf("cmd_ofsWriteFile run error...");
	}else{
		printf("cmd_ofsWriteFile run succeed...");
	}
	printf("tcp_send_program_G5:cmd_ofsWriteFile===== %d \n", ret);
	printf("fileName_G5 == %s \n", program.fileName);
	printf("fileType_G5 == %d \n", program.fileType);
	printf("fileLen_G5 == %d \n", program.fileLen);
	printf("fileCRC32_G5 == %d \n",program.fileCRC32);
	ret = bxDual_cmd_uart_ofsEndFileTransf(uartPort, baudRate);
	if(ret != 0){
		printf("cmd_ofsEndFileTransf run error...");
	}else{
		printf("cmd_ofsEndFileTransf run succeed...");
	}
	printf("tcp_send_program_G5:md_ofsWriteFile===== %d \n", ret);
	//删除本地内存中的节目
	bxDual_program_deleteProgram();
	bxDual_program_freeBuffer(&program);
	
}
void tcp_send_program_G6(Ouint8* ip, Ouint16 port)
{
	Oint8 ret;
	EQprogram_G6 program;
	memset((void*)&program, 0, sizeof(program));
	bxDual_program_IntegrateProgramFile_G6(&program);
	
	ret = bxDual_cmd_ofsStartFileTransf(ip, port);
	printf("ret =====cmd_ofsStartFileTransf===== %d \n", ret);
	if(ret != 0){
		printf("cmd_ofsStartFileTransf run error...");
	}else{
		printf("cmd_ofsStartFileTransf run succeed...");
	}

	ret = bxDual_cmd_ofsWriteFile(ip, port, program.dfileName, program.dfileType, program.dfileLen, 1, program.dfileAddre);
	if(ret != 0){
		printf("cmd_ofsWriteFile run error...");
	}else{
		printf("cmd_ofsWriteFile run succeed...");
	}
	printf("ret =====cmd_ofsWriteFile===== %d \n", ret);

	ret = bxDual_cmd_ofsWriteFile(ip, port, program.fileName, program.fileType, program.fileLen, 1, program.fileAddre);
	if(ret != 0){
		printf("cmd_ofsWriteFile run error...");
	}else{
		printf("cmd_ofsWriteFile run succeed...");
	}
	printf("ret =====cmd_ofsWriteFile===== %d \n", ret);

	ret = bxDual_cmd_ofsEndFileTransf(ip, port);
	if(ret != 0){
		printf("cmd_ofsEndFileTransf run error...");
	}else{
		printf("cmd_ofsEndFileTransf run succeed...");
	}
	printf("ret =====cmd_ofsEndFileTransf===== %d \n", ret);

	//删除本地内存中的节目
	bxDual_program_deleteProgram_G6();
	bxDual_program_freeBuffer_G6(&program);

}
//动态区
void dynamic_6(Ouint8* ip,BYTE cmb_ping_Color)
{
	Ouint8 DisplayMode = 0x02;		//显示方式:  0x00 –随机显示 0x01 –静止显示 0x02 –快速打出 0x03 –向左移动 0x04 –向左连移 0x05 –向上移动 0x06 –向上连移 0x07 –闪烁 ......
									//0x25 –向右移动  0x26 –向右连移  0x27 –向下移动  0x28 –向下连移

		Ouint8 ClearMode = 0;		//退出方式/清屏方式: 每一页的退出方式；
		Ouint8 Speed = 8;			//速度等级
		Ouint16 StayTime = 200;		//停留时间，单位为 10ms
		Ouint8 RepeatTime = 3;
		//PageStyle End.

		//显示内容和字体格式 begin----------------------------------------------
		EQfontData oFont;
		oFont.arrMode = eMULTILINE;
		oFont.fontSize = 10;
		oFont.color = eRED;
		oFont.fontBold = false;
		oFont.fontItalic = false; oFont.tdirection = pNORMAL;
		oFont.txtSpace = 0; oFont.Halign = 1; oFont.Valign = 2;
		Ouint8* fontName = (Ouint8*)"宋体";




		oFont.arrMode = eSINGLELINE;// eMULTILINE;//;
									//oFont.fontSize = 8;

		onbon_DynamicAreaInfo_G6 oTxtInfo;
		oTxtInfo.nType = 2;
		oTxtInfo.DisplayMode = DisplayMode;
		oTxtInfo.ClearMode = ClearMode;
		oTxtInfo.Speed = Speed;
		oTxtInfo.StayTime = StayTime;
		oTxtInfo.RepeatTime = RepeatTime;
		oTxtInfo.fontName = fontName;
		oTxtInfo.oFont = oFont;


		oTxtInfo.filePath =  (Ouint8*)"F:/work github/Debug HUB/BX-V-VI/bx.dual.cplus/src/lib/2Led0_0.png"; //(Ouint8*)"T1-0123456789-abcdefghijklmnopqrst-结束|";
		const Ouint8 cnst_InfoCount = 1;  //单区域内有多少个数据单元内容
			onbon_DynamicAreaInfo_G6 stDynamicAreaBaseInfo_6G[cnst_InfoCount];
			stDynamicAreaBaseInfo_6G[0] = oTxtInfo;
	Ouint8 uAreaId = 0;

	/*动态区运行模式：默认值=0x00
	0— 动态区数据循环显示。
	1— 动态区数据显示完成后静止显示最后一页数据。
	2— 动态区数据循环显示，超过设定时间后数据仍未更新时不再显示
	3— 动态区数据循环显示，超过设定时间后数据仍未更新时显示Logo 信息, Logo 信息即为动态区域的最后一页信息
	4— 动态区数据顺序显示，显示完最后一页后就不再显示
	*/
	Ouint8 RunMode = 0x02;
	Ouint16 Timeout = 3;		//Timeout 2 动态区数据超时时间，单位为秒
	Ouint8 RelateAllPro = 0;	//RelateAllPro 1 当该字节为 1 时，所有异步节目播放时都允许播放该动态区域；为 0 时，由接下来的规则来决定
	Ouint16 RelateProNum = 0;	//动态区域关联了多少个异步节目一旦关联了某个异步节目，则当该异步节目播放时允许播放该动态区域，否则，不允许播放该动态区域；以下的节目编号个数根据 RelateProNum 的值来确定，当该值为 0 时不发送；
	Ouint16* RelateProSerial = NULL;
	Ouint8 ImmePlay = 1;		//1 是否立即播放该字节为 0 时，该动态区域与异步节目一起播放；该字节为 1 时，异步节目停止播放，仅播放该动态区域该字节; 为 2 时，暂存该动态区域，当播放完节目编号最高的异步节目后播放该动态区域注意：当该字节为 0 时，RelateAllPro 到RelateProSerialN-1 的参数才有效，否则无效当该参数为 1 或 2 时，由于不与异步节目同时播放，为控制该动态区域能及时结束，可选择RunMode 参数为 2 或 4，当然也
	Ouint16 uAreaX = 0;
	Ouint16 uAreaY = 0;
	Ouint16 uWidth = 288;
	Ouint16 uHeight = 129;
	//EQareaframeHeader oFrame;  
	BxAreaFrmae_Dynamic_G6 oFrame;
	oFrame.AreaFFlag = 0;  //边框默认无边框
	oFrame.pStrFramePathFile = (Ouint8*)".\\backgroundPng\\led_bk1.png";

	EQscreenframeHeader_G6 oFrameHeader;
	oFrameHeader.FrameDispStype = 0x01;
	oFrameHeader.FrameDispSpeed = 0x10;
	oFrameHeader.FrameMoveStep = 0x01;
	oFrameHeader.FrameUnitLength = 32;
	oFrameHeader.FrameUnitWidth = 4;//2;
	oFrameHeader.FrameDirectDispBit = 0x01;

	oFrame.oAreaFrame = oFrameHeader;



	//添加语音数据/填充语音数据结构
	BXSound_6G oSoundG6;
	Ouint8* strSoundTxt = (Ouint8*)"动态区语音——文字和图片接口发送";
	Ouint8 nSize = sizeof(strSoundTxt);
	Ouint8 nStrLen = strlen((const char*)strSoundTxt);
	{
		oSoundG6.SoundFlag = 0x00;	//1 0x00 是否使能语音播放;0 表示不使能语音; 1 表示播放下文中;
		oSoundG6.SoundPerson = 0x01;	//1 0x00 发音人 该值范围是 0 - 5，共 6 种选择只有 SoundFlag（是否使能语音播放）为 1 时才发送该字节，否则不发送该值默认为 0
		oSoundG6.SoundVolum = 1;		//1 0x05 音量该值范围是 0~10，共 11 种，0表示静音只有 SoundFlag（是否使能语音播放）为 1 时才发送该字节，否则不发送该值默认为 5
		oSoundG6.SoundSpeed = 0x2;	//1 0x05 语速该值范围是 0~10，共 11 种只有 SoundFlag（是否使能语音播放）为 1 时才发送该字节，否则不发送该值默认为 5
		oSoundG6.SoundDataMode = 0x00;//1 0x00 SoundData 的编码格式：该值意义如下：0x00 GB2312; 0x01 GBK; 0x02 BIG5; 0x03 UNICODE只有 SoundFlag（是否使能语音播放）为 1 时才发送该字节，否则不发送
		oSoundG6.SoundReplayTimes = 0x01;// 0xffffffff;	//4 0x00000000 重播次数该值为 0，表示播放 1 次该值为 1，表示播放 2 次
											//......
											//该值为 0xffffffff，表示播放无限次只有 SoundFlag（是否使能语播放）为 1 时才发送该字节，否则不发送该值默认为 0
		oSoundG6.SoundReplayDelay = 200;	//4 0x00000000 重播时间间隔该值表示两次播放语音的时间间隔，单位为 10ms只有 SoundFlag（是否使能语音播放）为 1 时才发送该字节，否则不发送该值默认为 0
		oSoundG6.SoundReservedParaLen = 0x03;//1 0x03 语音参数保留参数长度
		oSoundG6.Soundnumdeal = 0x00;		//1 0 0：自动判断1：数字作号码处理 2：数字作数值处理只有当 SoundFlag 为 1 且SoundReservedParaLen不为 0才发送此参数
		oSoundG6.Soundlanguages = 0x00;		// 1 0 0：自动判断语种1：阿拉伯数字、度量单位、特殊符号等合成为中文2：阿拉伯数字、度量单位、特殊符号等合成为英文只有当 SoundFlag 为 1 且 SoundReservedParaLen不为 0才发送此参数（目前只支持中英文）
		oSoundG6.Soundwordstyle = 0x00;		// 1 0 0：自动判断发音方式1：字母发音方式2：单词发音方式只有当 SoundFlag 为 1 且SoundReservedParaLen不为 0才发送此参数
		oSoundG6.SoundDataLen = nStrLen;		// 4 语音数据长度; 只有 SoundFlag（是否使能语音播放）为 1 时才发送该字节，否则不发送
		oSoundG6.SoundData = strSoundTxt;			// N 语音数据只有 SoundFlag（是否使能语音播放）为 1 时才发送该字节，否则不发送

	}

	int nSendInfoCount = cnst_InfoCount;
	nSendInfoCount = 1;
	//bxDual_dynamicArea_SetDualPixel(eDOUBLE_COLOR_PIXTYPE_1);
	int nRet = bxDual_dynamicArea_AddAreaInfos_6G_V2(ip, 5005, (E_ScreenColor_G56)cmb_ping_Color,
		uAreaId,
		RunMode,
		Timeout,
		RelateAllPro,
		RelateProNum,
		RelateProSerial,
		ImmePlay,
		uAreaX, uAreaY, uWidth, uHeight,
		oFrame,
		nSendInfoCount,
		stDynamicAreaBaseInfo_6G,
		&oSoundG6
	);

}

void onbonTest_Dynamic_6G_Pic()
{

	unsigned char pIP[16] = "192.168.89.129";
	Ouint32 nPort = 5005;

	Ouint16 nAreaID = 0;
	E_ScreenColor_G56 eColor = E_ScreenColor_G56::eSCREEN_COLOR_DOUBLE;// eSCREEN_COLOR_THREE;


	EQpageHeader_G6 stPageHeader;
	stPageHeader.PageStyle = 0x00;
	stPageHeader.DisplayMode = 0x04;
	stPageHeader.ClearMode = 0x00;
	stPageHeader.Speed = 64;
	stPageHeader.StayTime = 0;// 500;
	stPageHeader.RepeatTime = 1;
	stPageHeader.ValidLen = 64;
	stPageHeader.CartoonFrameRate = 0x00;
	stPageHeader.BackNotValidFlag = 0x00;
	stPageHeader.arrMode = eSINGLELINE; //eMULTILINE;//
	stPageHeader.fontSize = 10;
	stPageHeader.color = eRED;
	stPageHeader.fontBold = false;
	stPageHeader.fontItalic = false;
	stPageHeader.tdirection = pNORMAL;
	stPageHeader.txtSpace = 0;
	stPageHeader.Valign = 1;
	stPageHeader.Halign = 1;

	stPageHeader.DisplayMode = 0x01;


	//bxDual_dynamicArea_DelArea_6G(pIP, 5005, 0xff);

	bxDual_dynamicArea_AddAreaPic_6G(pIP, 5005, eColor, nAreaID, 0, 0, 288, 192, &stPageHeader, (Ouint8*)"F:/work github/Debug HUB/BX-V-VI/bx.dual.cplus/src/lib/2Led0_0.png");

	//dynamicArea_DelArea_6G(pIP, 5005, 0xff);
	//dynamicAreaS_AddAreaPic_6G
	//dynamicArea_AddAreaPic_6G(pIP, 5005, eColor, nAreaID, 16 + 32 + 1, 0, 32, 32, &stPageHeader, (Ouint8*)"./图片测试文件/文明出行.bmp");
}

        //添加语音
void Creat_sound_6(Ouint16 areaID)
{
	Ouint8* str =(Ouint8*)"请张三到1号窗口取药";
    EQPicAreaSoundHeader_G6 pheader;
    pheader.SoundPerson = 3;
    pheader.SoundVolum = 5;
    pheader.SoundSpeed = 5;
    pheader.SoundDataMode = 0;
    pheader.SoundReplayTimes = 0;
    pheader.SoundReplayDelay = 1000;
    pheader.SoundReservedParaLen = 3;
    pheader.Soundnumdeal = 1;
    pheader.Soundlanguages = 1;
    pheader.Soundwordstyle = 1;
    //int err = bxDual_program_pictureAreaEnableSound_G6(areaID, &pheader, str);
}

void onbonTest_bxDual_dynamicArea_AddAreaWithTxt_5G(void)
{
	printf("*****************************************\n");
	printf("*************** test start **************\n");
	printf("*****************************************\n");
	printf("\n");

	Ouint8* pIP = (Ouint8*)"37.52.6.240";
	Ouint32 nPort = 5005;
	E_ScreenColor_G56 color = E_ScreenColor_G56::eSCREEN_COLOR_SINGLE;

	//先删除所有动态区
	bxDual_dynamicArea_DelArea_5G(pIP, nPort, 0xff);


	Ouint8 uAreaId = 0;
	Ouint8 RunMode = 0x00;
	/*0x00 动态区运行模式
	0— 动态区数据循环显示。
	1— 动态区数据显示完成后静止显示最后一页数据。
	2— 动态区数据循环显示，超过设定时间后数据仍未更新时不再显示
	3— 动态区数据循环显示，超过设定时间后数据仍未更新时显示Logo 信息, Logo 信息即为动态区域的最后一页信息
	4— 动态区数据顺序显示，显示完最后一页后就不再显示
	*/
	Ouint16 Timeout = 3;		//Timeout 2 动态区数据超时时间，单位为秒
	Ouint8 RelateAllPro = 0;	//RelateAllPro 1 当该字节为 1 时，所有异步节目播放时都允许播放该动态区域；为 0 时，由接下来的规则来决定
	Ouint16 RelateProNum = 0;	//动态区域关联了多少个异步节目一旦关联了某个异步节目，则当该异步节目播放时允许播放该动态区域，否则，不允许播放该动态区域；以下的节目编号个数根据 RelateProNum 的值来确定，当该值为 0 时不发送；
	Ouint16* RelateProSerial = NULL;
	Ouint8 ImmePlay = 1;		//1 是否立即播放该字节为 0 时，该动态区域与异步节目一起播放；该字节为 1 时，异步节目停止播放，仅播放该动态区域该字节; 为 2 时，暂存该动态区域，当播放完节目编号最高的异步节目后播放该动态区域注意：当该字节为 0 时，RelateAllPro 到RelateProSerialN-1 的参数才有效，否则无效当该参数为 1 或 2 时，由于不与异步节目同时播放，为控制该动态区域能及时结束，可选择RunMode 参数为 2 或 4，当然也
	Ouint16 uAreaX = 0;
	Ouint16 uAreaY = 0;
	Ouint16 uWidth = 128;
	Ouint16 uHeight = 416;
	EQareaframeHeader oFrame; oFrame.AreaFFlag = 0;
	//PageStyle begin-------------------------------------------------------------------------------------------------------------------------------------------
	Ouint8 DisplayMode = 3;		//显示方式:  0x00 –随机显示 0x01 –静止显示 0x02 –快速打出 0x03 –向左移动 0x04 –向左连移 0x05 –向上移动 0x06 –向上连移 0x07 –闪烁 ......
	Ouint8 ClearMode = 0;		//退出方式/清屏方式: 每一页的退出方式；
	Ouint8 Speed = 32;			//速度等级
	Ouint16 StayTime = 200;		//停留时间，单位为 10ms
	Ouint8 RepeatTime = 3;
	//PageStyle End.

	//显示内容和字体格式 begin----------------------------------------------
	EQfontData oFont;
	oFont.arrMode = eMULTILINE;
	oFont.fontSize = 20;
	oFont.color = eRED;
	oFont.fontBold = false;
	oFont.fontItalic = false; oFont.tdirection = pNORMAL;
	oFont.txtSpace = 0; oFont.Halign = 1; oFont.Valign = 2;
	Ouint8* fontName = (Ouint8*)"宋体";
	Ouint8* strAreaTxtContent = (Ouint8*)"5E1动态-图文-文本测试。";// "5代动态区1开始测试，一起来看看吧...会有新发现的。";


	bxDual_dynamicArea_AddAreaWithTxt_5G(pIP, nPort, color,
		uAreaId,
		RunMode,
		Timeout,
		RelateAllPro,
		RelateProNum,
		RelateProSerial,
		ImmePlay,
		uAreaX, uAreaY, uWidth, uHeight,
		oFrame,
		//PageStyle begin--------
		DisplayMode,
		ClearMode,
		Speed,
		StayTime,
		RepeatTime,
		//PageStyle End.

		//显示内容和字体格式 begin---------
		oFont,
		fontName,
		strAreaTxtContent
		//end.
	);

}