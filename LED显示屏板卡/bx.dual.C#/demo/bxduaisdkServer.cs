using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;

namespace LedSDKDemo_CSharp
{
    public class bxduaisdkServer
    {
        [DllImport("bx_sdk_dual_server.dll", CharSet = CharSet.Unicode)]
        public static extern int bxDual_InitSdk();
        [DllImport("bx_sdk_dual_server.dll", CharSet = CharSet.Unicode)]
        public static extern void bxDual_ReleaseSdk();
        [DllImport("bx_sdk_dual_server.dll", CharSet = CharSet.Unicode)]
        public static extern int bxDual_Start_Server(int port);

        [DllImport("bx_sdk_dual_server.dll", CharSet = CharSet.Unicode)]
        public static extern int bxDual_Stop_Server(int pServer);

        [DllImport("bx_sdk_dual_server.dll", CharSet = CharSet.Unicode)]
        public static extern int bxDual_Get_Port_Barcode(byte[] barcode);

        [DllImport("bx_sdk_dual_server.dll", CharSet = CharSet.Unicode)]
        public static extern int bxDual_Get_CardList(byte[] cards);
    }
}
