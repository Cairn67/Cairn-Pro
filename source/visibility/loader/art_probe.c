/* Opaque unused JNI arguments; no JNI table access in this initial probe. */
// ART runtime probe implementation.
__attribute__((visibility("default"))) int Java_com_cairn_visibility_ArtProbe_nativeHandshake(void *env,void *clazz,int abi){
 (void)env;(void)clazz;return abi==1?0x43414952:-1;
}
