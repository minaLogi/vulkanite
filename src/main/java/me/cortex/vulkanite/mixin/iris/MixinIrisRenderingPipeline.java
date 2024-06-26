package me.cortex.vulkanite.mixin.iris;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap.Entry;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.VulkanPipeline;
import me.cortex.vulkanite.compat.*;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline {
  
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private CustomTextureManager customTextureManager;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Shadow @Final private float sunPathRotation;
    @Unique private RaytracingShaderSet[] rtShaderPasses = null;
    @Unique private VContext ctx;
    @Unique private VulkanPipeline pipeline;

    @Unique
    private VGImage[] getCustomTextures() {
        Object2ObjectMap<String, TextureAccess> texturesBinary = customTextureManager.getIrisCustomTextures();
        Object2ObjectMap<String, TextureAccess> texturesPNGs = customTextureManager.getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW);

        List<Entry<String, TextureAccess>> entryList = new ArrayList<>();
        entryList.addAll(texturesBinary.object2ObjectEntrySet());
        entryList.addAll(texturesPNGs.object2ObjectEntrySet());

        entryList.sort(Comparator.comparing(Entry::getKey));

        return entryList.stream()
                .map(entry -> ((IVGImage) entry.getValue()).getVGImage())
                .toArray(VGImage[]::new);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShader(ProgramSet set, CallbackInfo ci) {
        ctx = Vulkanite.INSTANCE.getCtx();
        var passes = ((IGetRaytracingSource)set).getRaytracingSource();
        if (passes != null) {
            rtShaderPasses = new RaytracingShaderSet[passes.length];
            for (int i = 0; i < passes.length; i++) {
                rtShaderPasses[i] = new RaytracingShaderSet(ctx, passes[i]);
            }
        }
        // Still create this, later down the line we might add Vulkan compute pipelines or mesh shading, etc.
        pipeline = new VulkanPipeline(ctx, Vulkanite.INSTANCE.getAccelerationManager(), rtShaderPasses, set.getPackDirectives().getBufferObjects().keySet().toArray(new int[0]), getCustomTextures());
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void renderShadows(LevelRendererAccessor par1, Camera par2, CallbackInfo ci) {
        ShaderStorageBuffer[] buffers = new ShaderStorageBuffer[0];

        if(shaderStorageBufferHolder != null) {
            buffers = ((ShaderStorageBufferHolderAccessor)shaderStorageBufferHolder).getBuffers();
        }

        List<VGImage> outImgs = new ArrayList<>();
        for (int i = 0; i < renderTargets.getRenderTargetCount(); i++) {
            outImgs.add(((IRenderTargetVkGetter)renderTargets.getOrCreate(i)).getMain());
        }

        MixinCelestialUniforms celestialUniforms = (MixinCelestialUniforms)(Object) new CelestialUniforms(this.sunPathRotation);

        pipeline.renderPostShadows(outImgs, par2, buffers, celestialUniforms);
    }

    @Inject(method = "destroyShaders", at = @At("TAIL"))
    private void destory(CallbackInfo ci) {
        ctx.cmd.waitQueueIdle(0);
        if (rtShaderPasses != null) {
            for (var pass : rtShaderPasses) {
                pass.delete();
            }
        }
        pipeline.destory();
        rtShaderPasses = null;
        pipeline = null;
    }
}
