#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_tensor_buffer_requirements.h"

namespace {
#define IMAGE_API(F) \
 F(LiteRtCreateEnvironment) F(LiteRtDestroyEnvironment) \
 F(LiteRtCreateModelFromFile) F(LiteRtDestroyModel) F(LiteRtCreateOptions) F(LiteRtDestroyOptions) \
 F(LiteRtSetOptionsHardwareAccelerators) F(LiteRtCreateCompiledModel) F(LiteRtDestroyCompiledModel) \
 F(LiteRtGetModelSignature) F(LiteRtGetNumSignatureInputs) F(LiteRtGetNumSignatureOutputs) \
 F(LiteRtGetSignatureInputTensorByIndex) F(LiteRtGetSignatureOutputTensorByIndex) F(LiteRtGetRankedTensorType) \
 F(LiteRtGetCompiledModelInputBufferRequirements) F(LiteRtGetCompiledModelOutputBufferRequirements) \
 F(LiteRtCreateManagedTensorBufferFromRequirements) F(LiteRtDestroyTensorBuffer) F(LiteRtGetTensorBufferPackedSize) \
 F(LiteRtLockTensorBuffer) F(LiteRtUnlockTensorBuffer) F(LiteRtRunCompiledModel) F(LiteRtCompiledModelIsNonCpuFullyAccelerated)
struct Api {
#define DECLARE(name) decltype(&::name) name = nullptr;
 IMAGE_API(DECLARE)
#undef DECLARE
 void* library = nullptr;
 std::string dispatch;
} api;
struct Error : std::runtime_error {
 Error(const char* step, int status) : std::runtime_error(std::string("Image runtime ") + step +
       " failed (API " + std::to_string(status) + ").") { }
};
void Check(LiteRtStatus status, const char* step) { if (status != kLiteRtStatusOk) throw Error(step,status); }
std::string String(JNIEnv* env, jstring text) {
 const char* value = env->GetStringUTFChars(text,nullptr);
 if (!value) throw std::runtime_error("Image argument allocation failed.");
 std::string result(value); env->ReleaseStringUTFChars(text,value); return result;
}
void Raise(JNIEnv* env,const std::exception& error) { env->ThrowNew(env->FindClass("java/io/IOException"),error.what()); }
struct State {
 LiteRtEnvironment environment=nullptr; LiteRtModel model=nullptr;
 LiteRtOptions options=nullptr; LiteRtCompiledModel compiled=nullptr;
 std::vector<LiteRtTensorBuffer> inputs,outputs;
 ~State() {
  for(auto buffer:outputs) api.LiteRtDestroyTensorBuffer(buffer);
  for(auto buffer:inputs) api.LiteRtDestroyTensorBuffer(buffer);
  if(compiled) api.LiteRtDestroyCompiledModel(compiled);
  if(options) api.LiteRtDestroyOptions(options);
  if(model) api.LiteRtDestroyModel(model);
  if(environment) api.LiteRtDestroyEnvironment(environment);
 }
};
void Buffers(State& state,LiteRtSignature signature,bool input) {
 LiteRtParamIndex count=0;
 Check(input?api.LiteRtGetNumSignatureInputs(signature,&count):api.LiteRtGetNumSignatureOutputs(signature,&count),"io_count");
 if(count>4 || (!input&&count!=1)) throw std::runtime_error("Unexpected SDXS IO contract.");
 for(size_t i=0;i<count;i++) {
  LiteRtTensor tensor=nullptr;
  Check(input?api.LiteRtGetSignatureInputTensorByIndex(signature,i,&tensor):api.LiteRtGetSignatureOutputTensorByIndex(signature,i,&tensor),"tensor");
  LiteRtRankedTensorType type;
  Check(api.LiteRtGetRankedTensorType(tensor,&type),"tensor_type");
  LiteRtTensorBufferRequirements requirements=nullptr;
  Check(input?api.LiteRtGetCompiledModelInputBufferRequirements(state.compiled,0,i,&requirements):api.LiteRtGetCompiledModelOutputBufferRequirements(state.compiled,0,i,&requirements),"buffer_requirements");
  LiteRtTensorBuffer buffer=nullptr;
  Check(api.LiteRtCreateManagedTensorBufferFromRequirements(state.environment,&type,requirements,&buffer),"allocate_buffer");
  (input?state.inputs:state.outputs).push_back(buffer);
 }
}
}

extern "C" JNIEXPORT void JNICALL Java_dev_inferdroid_images_NativeImages_configure(
 JNIEnv* env,jclass,jstring runtime,jstring dispatch,jstring log) {
 try {
  umask(0077); struct rlimit limit={0,0}; setrlimit(RLIMIT_CORE,&limit);
  std::string log_path=String(env,log);
  int fd=open(log_path.c_str(),O_WRONLY|O_CREAT|O_APPEND|O_CLOEXEC,0600);
  if(fd<0) throw std::runtime_error("Cannot capture image runtime output.");
  const bool captured=dup2(fd,STDOUT_FILENO)>=0&&dup2(fd,STDERR_FILENO)>=0; close(fd);
  if(!captured) throw std::runtime_error("Cannot redirect image runtime output.");
  api.dispatch=String(env,dispatch);
  if(api.library) return;
  api.library=dlopen(String(env,runtime).c_str(),RTLD_NOW|RTLD_LOCAL);
  if(!api.library) throw std::runtime_error("Cannot load the private image runtime; inspect local diagnostics.");
#define LOAD(name) api.name=reinterpret_cast<decltype(api.name)>(dlsym(api.library,#name)); if(!api.name) throw std::runtime_error("Missing image API entry point: " #name);
  IMAGE_API(LOAD)
#undef LOAD
 } catch(const std::exception& error) { Raise(env,error); }
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_dev_inferdroid_images_NativeImages_run(
 JNIEnv* env,jclass,jstring model,jobjectArray values,jboolean hybrid,jint expected_bytes) {
 try {
  if(!api.LiteRtRunCompiledModel) throw std::runtime_error("Image runtime is not configured.");
  State state;
  LiteRtEnvOption option={}; option.tag=kLiteRtEnvOptionTagDispatchLibraryDir;
  option.value.type=kLiteRtAnyTypeString; option.value.str_value=api.dispatch.c_str();
  Check(api.LiteRtCreateEnvironment(1,&option,&state.environment),"environment");
  Check(api.LiteRtCreateModelFromFile(state.environment,String(env,model).c_str(),&state.model),"load_model");
  Check(api.LiteRtCreateOptions(&state.options),"options");
  Check(api.LiteRtSetOptionsHardwareAccelerators(state.options,hybrid?kLiteRtHwAcceleratorCpu|kLiteRtHwAcceleratorNpu:kLiteRtHwAcceleratorNpu),"accelerators");
  Check(api.LiteRtCreateCompiledModel(state.environment,state.model,state.options,&state.compiled),"prepare");
  LiteRtSignature signature=nullptr;
  Check(api.LiteRtGetModelSignature(state.model,0,&signature),"signature");
  Buffers(state,signature,true); Buffers(state,signature,false);
  if(env->GetArrayLength(values)!=static_cast<jsize>(state.inputs.size())) throw std::runtime_error("Wrong SDXS input count.");
  std::vector<bool> used(state.inputs.size(),false);
  for(auto buffer:state.inputs) {
   size_t size=0; Check(api.LiteRtGetTensorBufferPackedSize(buffer,&size),"input_size");
   jbyteArray chosen=nullptr; int match=-1;
   for(size_t i=0;i<used.size();i++) {
    auto array=static_cast<jbyteArray>(env->GetObjectArrayElement(values,i));
    bool matches=!used[i]&&static_cast<size_t>(env->GetArrayLength(array))==size;
    if(matches) { if(match>=0) throw std::runtime_error("Ambiguous SDXS input mapping."); chosen=array; match=i; }
    else env->DeleteLocalRef(array);
   }
   if(match<0) throw std::runtime_error("Wrong SDXS input size.");
   used[match]=true; void* address=nullptr;
   Check(api.LiteRtLockTensorBuffer(buffer,&address,kLiteRtTensorBufferLockModeWrite),"write_input");
   env->GetByteArrayRegion(chosen,0,size,static_cast<jbyte*>(address));
   Check(api.LiteRtUnlockTensorBuffer(buffer),"unlock_input"); env->DeleteLocalRef(chosen);
   if(env->ExceptionCheck()) return nullptr;
  }
  Check(api.LiteRtRunCompiledModel(state.compiled,0,state.inputs.size(),state.inputs.data(),state.outputs.size(),state.outputs.data()),"invoke");
  bool full=false; Check(api.LiteRtCompiledModelIsNonCpuFullyAccelerated(state.compiled,&full),"acceleration_check");
  if(!hybrid&&!full) throw std::runtime_error("The decoder did not run fully on NPU.");
  size_t size=0; Check(api.LiteRtGetTensorBufferPackedSize(state.outputs[0],&size),"output_size");
  if(expected_bytes<=0||expected_bytes>4*1024*1024||size!=static_cast<size_t>(expected_bytes)) throw std::runtime_error("Wrong SDXS output size.");
  jbyteArray result=env->NewByteArray(size); if(!result) return nullptr;
  void* address=nullptr; Check(api.LiteRtLockTensorBuffer(state.outputs[0],&address,kLiteRtTensorBufferLockModeRead),"read_output");
  env->SetByteArrayRegion(result,0,size,static_cast<jbyte*>(address));
  Check(api.LiteRtUnlockTensorBuffer(state.outputs[0]),"unlock_output"); return result;
 } catch(const std::exception& error) { Raise(env,error); return nullptr; }
}
