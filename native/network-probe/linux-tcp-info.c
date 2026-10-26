/* BO-owned read-only Linux TCP_INFO bridge. No socket creation/options/send/close or table scans. */
#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <linux/tcp.h>
#include <stddef.h>
#include <string.h>
#include <errno.h>

JNIEXPORT jint JNICALL Java_com_PinkCats_bandwidthoptimizer_networkprobe_LinuxTcpInfo_query
  (JNIEnv *env, jclass owner, jint descriptor, jlongArray output) {
    struct tcp_info info;
    socklen_t length=sizeof(info);
    jlong values[2];
    (void)owner;
    if(descriptor<0 || output==NULL || (*env)->GetArrayLength(env,output)!=2) return -1;
    memset(&info,0,sizeof(info));
    if(getsockopt(descriptor,IPPROTO_TCP,TCP_INFO,&info,&length)!=0) return errno;
    if(length<offsetof(struct tcp_info,tcpi_data_segs_out)+sizeof(info.tcpi_data_segs_out)) return -2;
    /* Data segments, including retransmissions; excludes pure ACK packets from the denominator. */
    values[0]=(jlong)info.tcpi_data_segs_out;
    values[1]=(jlong)info.tcpi_total_retrans;
    (*env)->SetLongArrayRegion(env,output,0,2,values);
    return 0;
}
