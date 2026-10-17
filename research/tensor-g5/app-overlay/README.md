# Image generation is integrated into InferDroid

The image UI, Java/JNI implementation, resources, service/API integration, and
tests now live in the normal `app/src/` source sets. Use the standard Android
Studio/Gradle build and the regular `dev.inferdroid` application on port 8080.
The generated-source overlay and alternate application build were removed.

See [image generation setup and verification](../../../docs/image-generation.md)
and [model export and compilation](../tooling/README.md).
