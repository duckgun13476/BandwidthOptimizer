/* BO-owned read-only Winsock statistics bridge. No socket creation, options, send or close. */
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include <mstcpip.h>
#include <jni.h>

JNIEXPORT jint JNICALL Java_com_PinkCats_bandwidthoptimizer_networkprobe_WindowsTcpInfo_query
  (JNIEnv *env, jclass owner, jobject channel, jlongArray output) {
    jclass socket_impl, selector_interface;
    jmethodID fd_method;
    jint descriptor;
    ULONG version = 0;
    DWORD returned = 0;
    TCP_INFO_v0 info;
    jlong values[15];
    int result;
    (void)owner;
    if (channel == NULL || output == NULL || (*env)->GetArrayLength(env, output) != 15) return -1;
    socket_impl = (*env)->FindClass(env, "sun/nio/ch/SocketChannelImpl");
    selector_interface = (*env)->FindClass(env, "sun/nio/ch/SelChImpl");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return -2; }
    if (socket_impl == NULL || selector_interface == NULL || !(*env)->IsInstanceOf(env, channel, socket_impl)) return -3;
    /* Public SelChImpl accessor, guarded by Java runtime version and actual implementation class.
       No private field access, guessed layout, offset, table scan or foreign process handle. */
    fd_method = (*env)->GetMethodID(env, selector_interface, "getFDVal", "()I");
    if (fd_method == NULL) { (*env)->ExceptionClear(env); return -4; }
    descriptor = (*env)->CallIntMethod(env, channel, fd_method);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); return -5; }
    if (descriptor == -1) return WSAENOTSOCK;
    ZeroMemory(&info, sizeof(info));
    result = WSAIoctl((SOCKET)(UINT_PTR)(UINT32)descriptor, SIO_TCP_INFO,
            &version, sizeof(version), &info, sizeof(info), &returned, NULL, NULL);
    if (result == SOCKET_ERROR) return WSAGetLastError();
    if (returned != sizeof(info)) return -6;
    values[0] = (jlong)info.State;
    values[1] = (jlong)info.ConnectionTimeMs;
    values[2] = (jlong)info.RttUs;
    values[3] = (jlong)info.MinRttUs;
    values[4] = (jlong)info.BytesInFlight;
    values[5] = (jlong)info.Cwnd;
    values[6] = (jlong)info.SndWnd;
    values[7] = (jlong)info.RcvWnd;
    values[8] = (jlong)info.BytesOut;
    values[9] = (jlong)info.BytesIn;
    values[10] = (jlong)info.BytesRetrans;
    values[11] = (jlong)info.FastRetrans;
    values[12] = (jlong)info.DupAcksIn;
    values[13] = (jlong)info.TimeoutEpisodes;
    values[14] = (jlong)info.SynRetrans;
    (*env)->SetLongArrayRegion(env, output, 0, 15, values);
    return 0;
}
