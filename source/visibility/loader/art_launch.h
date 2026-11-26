// ART launch helpers for the loader probes.
#ifndef CAIRN_ART_LAUNCH_H
#define CAIRN_ART_LAUNCH_H

static int cairn_art_env(const char *dex){
 FILE *f=fopen("/data/system/environ/classpath","r");if(!f)return -1;
 char *line=NULL;size_t cap=0;int found=0,failed=0;
 while(getline(&line,&cap,f)>=0){
  const char *keys[]={"BOOTCLASSPATH","DEX2OATBOOTCLASSPATH"};
  for(int i=0;i<2;i++){char prefix[64];snprintf(prefix,sizeof(prefix),"export %s ",keys[i]);size_t n=strlen(prefix);
   if(!strncmp(line,prefix,n)){char *v=line+n;v[strcspn(v,"\r\n")]=0;if(!*v||setenv(keys[i],v,1)<0)failed=1;else found|=1<<i;}
  }
 }
 free(line);fclose(f);if(failed||found!=3)return -1;
 const char *keys[]={"ANDROID_DATA","ANDROID_ROOT","ANDROID_ART_ROOT","ANDROID_I18N_ROOT","ANDROID_TZDATA_ROOT","CLASSPATH"};
 const char *values[]={"/data","/system","/apex/com.android.art","/apex/com.android.i18n","/apex/com.android.tzdata",dex};
 for(int i=0;i<6;i++)if(setenv(keys[i],values[i],1)<0)return -1;
 return 0;
}
static void cairn_art_exec(char **argv,const char *logpath){
 if(cairn_art_env(argv[2])<0){perror("ART_ENV_FAILED");_exit(15);}
 fprintf(stderr,"ART_EXEC_START\n");
 execl("/system/bin/app_process64","app_process64","/system/bin","com.cairn.visibility.ArtProbe",argv[3],logpath,(char*)NULL);
 perror("ART_EXEC_FAILED");_exit(16);
}
#endif
