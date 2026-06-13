package yesman.epicfight.client.renderer.patched.layer;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.annotation.Nullable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import yesman.epicfight.api.animation.JointTransform;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.client.model.MeshPartDefinition;
import yesman.epicfight.api.client.model.SingleGroupVertexBuilder;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.client.model.transformer.GeoModelTransformer;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.api.utils.math.Vec2f;
import yesman.epicfight.api.utils.math.Vec3f;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

/**
 * EpicFight custom layer — regrowth de extremidades compatible con el sistema EF.
 *
 * applyJointHiding():
 *   SEVERED              → scale(0) — invisible
 *   REVERSING prog <75%  → scale(0) — EF body mesh oculto; nuestro SkinnedMesh
 *                                     usa un poses array separado con growthScale
 *   REVERSING prog ≥75%  → nada    — EF body mesh a escala normal
 *
 * renderLayer():
 *   Para REVERSING < 75%: re-computa poses con growthScale en los joints afectados
 *   y renderiza el SkinnedMesh procedural con esas poses. Sin z-fighting porque
 *   el EF body mesh está a scale(0) y solo nuestro mesh es visible.
 */
@OnlyIn(Dist.CLIENT)
public class EFLimbRegrowthLayer<E extends LivingEntity, T extends LivingEntityPatch<E>, M extends HumanoidModel<E>>
        extends PatchedLayer<E, T, M, RenderLayer<E, M>> {

    private static final float PHASE_BONE   = 0.25f;
    private static final float PHASE_MUSCLE = 0.50f;
    private static final float PHASE_FLESH  = 0.75f;
    private static final float MIN_SCALE    = 0.05f;

    // ── Reflexión ────────────────────────────────────────────────────────────
    private static volatile boolean reflectInit = false;
    private static Method cacheGet, snapGetState, snapGetProg;
    private static Object[] limbTypeValues;
    private static int idxRA=1,idxLA=0,idxRL=3,idxLL=2,idxH=4;
    private static int ordSevered=0,ordReversing=1;

    private static void ensureReflect() {
        if (reflectInit) return; reflectInit = true;
        try {
            Class<?> CC=Class.forName("net.mcreator.jujutsucraft.addon.limb.ClientLimbCache");
            Class<?> SC=Class.forName("net.mcreator.jujutsucraft.addon.limb.ClientLimbCache$EntityLimbSnapshot");
            Class<?> LC=Class.forName("net.mcreator.jujutsucraft.addon.limb.LimbType");
            Class<?> ST=Class.forName("net.mcreator.jujutsucraft.addon.limb.LimbState");
            cacheGet=CC.getMethod("get",int.class);
            snapGetState=SC.getMethod("getState",LC);
            snapGetProg=SC.getMethod("getRegenProgress",LC);
            limbTypeValues=(Object[])LC.getMethod("values").invoke(null);
            for(int i=0;i<limbTypeValues.length;i++) switch(((Enum<?>)limbTypeValues[i]).name()){
                case"LEFT_ARM"->idxLA=i; case"RIGHT_ARM"->idxRA=i;
                case"LEFT_LEG"->idxLL=i; case"RIGHT_LEG"->idxRL=i; case"HEAD"->idxH=i;}
            Object[] sv=(Object[])ST.getMethod("values").invoke(null);
            for(int i=0;i<sv.length;i++) switch(((Enum<?>)sv[i]).name()){
                case"SEVERED"->ordSevered=i; case"REVERSING"->ordReversing=i;}
        } catch(Throwable ignored){cacheGet=null;}
    }

    // ── Joints por miembro ───────────────────────────────────────────────────
    private static final String[] J_ARM_R={"Shoulder_R","Arm_R","Elbow_R","Hand_R","Tool_R"};
    private static final String[] J_ARM_L={"Shoulder_L","Arm_L","Elbow_L","Hand_L","Tool_L"};
    private static final String[] J_LEG_R={"Thigh_R","Leg_R","Knee_R"};
    private static final String[] J_LEG_L={"Thigh_L","Leg_L","Knee_L"};
    private static final String   J_HEAD ="Head";

    // ── SkinnedMesh lazy ─────────────────────────────────────────────────────
    private static SkinnedMesh meshArmR,meshArmL,meshLegR,meshLegL,meshHead;
    private static SkinnedMesh getMeshArmR(){if(meshArmR==null)meshArmR=buildBox(0.24f,0.51f,0.74f,1.51f,-0.135f,0.135f,11);return meshArmR;}
    private static SkinnedMesh getMeshArmL(){if(meshArmL==null)meshArmL=buildBox(-0.51f,-0.24f,0.74f,1.51f,-0.135f,0.135f,16);return meshArmL;}
    private static SkinnedMesh getMeshLegR(){if(meshLegR==null)meshLegR=buildBox(0.015f,0.285f,-0.01f,0.76f,-0.135f,0.135f,1);return meshLegR;}
    private static SkinnedMesh getMeshLegL(){if(meshLegL==null)meshLegL=buildBox(-0.285f,-0.015f,-0.01f,0.76f,-0.135f,0.135f,4);return meshLegL;}
    private static SkinnedMesh getMeshHead(){if(meshHead==null)meshHead=buildBox(-0.26f,0.26f,1.49f,2.01f,-0.26f,0.26f,9);return meshHead;}

    // ── Texturas ─────────────────────────────────────────────────────────────
    private static ResourceLocation boneTex,muscleTex,fleshTex;

    // ════════════════════════════════════════════════════════════════════════
    // 1. JOINT HIDING — escala 0 tanto para SEVERED como para REVERSING <75%.
    //    El visual de crecimiento lo maneja nuestro SkinnedMesh en renderLayer.
    // ════════════════════════════════════════════════════════════════════════
    public static void applyJointHiding(LivingEntity entity, Pose pose) {
        ensureReflect(); if(cacheGet==null) return;
        Object snap=getSnapshot(entity.getId()); if(snap==null) return;
        hideIfNeeded(snap,idxRA,J_ARM_R,pose);
        hideIfNeeded(snap,idxLA,J_ARM_L,pose);
        hideIfNeeded(snap,idxRL,J_LEG_R,pose);
        hideIfNeeded(snap,idxLL,J_LEG_L,pose);
        Object lt=limbTypeValues[idxH]; int st=stOrd(snap,lt);
        if(st==ordSevered||(st==ordReversing&&prog(snap,lt)<PHASE_FLESH))
            pose.putJointData(J_HEAD,JointTransform.scale(new Vec3f(0f,0f,0f)));
    }

    private static void hideIfNeeded(Object snap,int idx,String[] joints,Pose pose){
        Object lt=limbTypeValues[idx]; int st=stOrd(snap,lt);
        if(st==ordSevered||(st==ordReversing&&prog(snap,lt)<PHASE_FLESH)){
            JointTransform z=JointTransform.scale(new Vec3f(0f,0f,0f));
            for(String j:joints) pose.putJointData(j,z);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2. VISUAL REGROWTH
    //    Computa un Pose separado con growthScale en los joints REVERSING,
    //    obtiene las matrices de ese pose y renderiza nuestro SkinnedMesh.
    //    El EF body mesh ya está a scale(0) así que no hay solapamiento.
    // ════════════════════════════════════════════════════════════════════════
    @Override
    protected void renderLayer(T entitypatch, E entity,
                               @Nullable RenderLayer<E,M> vanillaLayer,
                               PoseStack ps, MultiBufferSource buf,
                               int light, OpenMatrix4f[] poses,
                               float bob,float yRot,float xRot,float partialTicks){
        ensureReflect(); if(cacheGet==null) return;
        Object snap=getSnapshot(entity.getId()); if(snap==null) return;

        // Verificar si hay algún miembro en fase activa
        boolean any=false;
        for(int idx:new int[]{idxRA,idxLA,idxRL,idxLL,idxH}){
            if(stOrd(snap,limbTypeValues[idx])==ordReversing&&prog(snap,limbTypeValues[idx])<PHASE_FLESH){any=true;break;}
        }
        if(!any) return;

        // Obtener el pose base animado y agregarle growthScale en los joints afectados
        Armature arm=entitypatch.getArmature();
        Pose growthPose=entitypatch.getAnimator().getPose(partialTicks);

        addGrowthScale(snap,idxRA,J_ARM_R,growthPose);
        addGrowthScale(snap,idxLA,J_ARM_L,growthPose);
        addGrowthScale(snap,idxRL,J_LEG_R,growthPose);
        addGrowthScale(snap,idxLL,J_LEG_L,growthPose);
        addGrowthScaleHead(snap,growthPose);

        // Computar matrices desde el growthPose
        arm.setPose(growthPose);
        OpenMatrix4f[] growthPoses=arm.getPoseMatrices();

        // Renderizar SkinnedMesh con textura procedural
        drawMesh(snap,idxRA,getMeshArmR(),arm,growthPoses,ps,buf,light);
        drawMesh(snap,idxLA,getMeshArmL(),arm,growthPoses,ps,buf,light);
        drawMesh(snap,idxRL,getMeshLegR(),arm,growthPoses,ps,buf,light);
        drawMesh(snap,idxLL,getMeshLegL(),arm,growthPoses,ps,buf,light);
        drawMesh(snap,idxH, getMeshHead(),arm,growthPoses,ps,buf,light);
    }

    private static void addGrowthScale(Object snap,int idx,String[] joints,Pose pose){
        Object lt=limbTypeValues[idx];
        if(stOrd(snap,lt)!=ordReversing) return;
        float p=prog(snap,lt); if(p>=PHASE_FLESH) return;
        float s=Math.max(MIN_SCALE,easeOut(p/PHASE_FLESH));
        JointTransform jt=JointTransform.scale(new Vec3f(s,s,s));
        for(String j:joints) pose.putJointData(j,jt);
    }

    private static void addGrowthScaleHead(Object snap,Pose pose){
        Object lt=limbTypeValues[idxH];
        if(stOrd(snap,lt)!=ordReversing) return;
        float p=prog(snap,lt); if(p>=PHASE_FLESH) return;
        float s=Math.max(MIN_SCALE,easeOut(p/PHASE_FLESH));
        pose.putJointData(J_HEAD,JointTransform.scale(new Vec3f(s,s,s)));
    }

    private static void drawMesh(Object snap,int idx,SkinnedMesh mesh,
                                  Armature arm,OpenMatrix4f[] growthPoses,
                                  PoseStack ps,MultiBufferSource buf,int light){
        Object lt=limbTypeValues[idx];
        if(stOrd(snap,lt)!=ordReversing) return;
        float p=prog(snap,lt); if(p>=PHASE_FLESH) return;
        ResourceLocation tex=p<PHASE_BONE?getBoneTex():p<PHASE_MUSCLE?getMuscleTex():getFleshTex();
        mesh.initialize();
        mesh.draw(ps,buf,RenderType.armorCutoutNoCull(tex),light,1f,1f,1f,1f,OverlayTexture.NO_OVERLAY,arm,growthPoses);
    }

    // ── SkinnedMesh builder ──────────────────────────────────────────────────
    private static SkinnedMesh buildBox(float x0,float x1,float y0,float y1,float z0,float z1,int joint){
        List<SingleGroupVertexBuilder> verts=Lists.newArrayList();
        Map<MeshPartDefinition,IntList> idxs=Maps.newHashMap();
        MeshPartDefinition part=GeoModelTransformer.GeoMeshPartDefinition.of("limb_r");
        int[]ctr={0};
        face(verts,idxs,part,ctr,joint,x1,y0,z0,0,0,x1,y1,z0,0,1,x1,y1,z1,1,1,x1,y0,z1,1,0,1,0,0);
        face(verts,idxs,part,ctr,joint,x0,y0,z1,0,0,x0,y1,z1,0,1,x0,y1,z0,1,1,x0,y0,z0,1,0,-1,0,0);
        face(verts,idxs,part,ctr,joint,x0,y1,z0,0,0,x0,y1,z1,0,1,x1,y1,z1,1,1,x1,y1,z0,1,0,0,1,0);
        face(verts,idxs,part,ctr,joint,x0,y0,z1,0,0,x0,y0,z0,0,1,x1,y0,z0,1,1,x1,y0,z1,1,0,0,-1,0);
        face(verts,idxs,part,ctr,joint,x0,y0,z1,0,0,x1,y0,z1,0,1,x1,y1,z1,1,1,x0,y1,z1,1,0,0,0,1);
        face(verts,idxs,part,ctr,joint,x1,y0,z0,0,0,x0,y0,z0,0,1,x0,y1,z0,1,1,x1,y1,z0,1,0,0,0,-1);
        return SingleGroupVertexBuilder.loadVertexInformation(verts,idxs);
    }

    private static void face(List<SingleGroupVertexBuilder>verts,Map<MeshPartDefinition,IntList>idxs,
                              MeshPartDefinition part,int[]ctr,int joint,
                              float x0,float y0,float z0,float u0,float v0,
                              float x1,float y1,float z1,float u1,float v1,
                              float x2,float y2,float z2,float u2,float v2,
                              float x3,float y3,float z3,float u3,float v3,
                              float nx,float ny,float nz){
        addV(verts,joint,x0,y0,z0,u0,v0,nx,ny,nz); addV(verts,joint,x1,y1,z1,u1,v1,nx,ny,nz);
        addV(verts,joint,x2,y2,z2,u2,v2,nx,ny,nz); addV(verts,joint,x3,y3,z3,u3,v3,nx,ny,nz);
        int b=ctr[0]; ctr[0]+=4;
        IntList il=idxs.computeIfAbsent(part,k->new IntArrayList());
        il.add(b);il.add(b+1);il.add(b+2);il.add(b);il.add(b+2);il.add(b+3);
    }

    private static void addV(List<SingleGroupVertexBuilder>verts,int joint,
                              float x,float y,float z,float u,float v,float nx,float ny,float nz){
        verts.add(new SingleGroupVertexBuilder()
            .setPosition(new Vec3f(x,y,z)).setNormal(new Vec3f(nx,ny,nz))
            .setTextureCoordinate(new Vec2f(u,v))
            .setEffectiveJointIDs(new Vec3f(joint,0,0))
            .setEffectiveJointWeights(new Vec3f(1f,0f,0f))
            .setEffectiveJointNumber(1));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private static Object getSnapshot(int id){try{return cacheGet.invoke(null,id);}catch(Throwable e){return null;}}
    private static int stOrd(Object snap,Object lt){try{return((Enum<?>)snapGetState.invoke(snap,lt)).ordinal();}catch(Throwable e){return-1;}}
    private static float prog(Object snap,Object lt){try{return((Number)snapGetProg.invoke(snap,lt)).floatValue();}catch(Throwable e){return 0f;}}
    private static float easeOut(float t){float i=1-t;return 1-i*i*i;}

    // ── Texturas procedurales ────────────────────────────────────────────────
    private static ResourceLocation getBoneTex(){
        if(boneTex!=null)return boneTex; boneTex=new ResourceLocation("epicfight","dynamic/limb_bone");
        NativeImage img=new NativeImage(NativeImage.Format.RGBA,16,16,false);Random r=new Random(45214L);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){int b=210+r.nextInt(30),g=b-5-r.nextInt(10),bl=b-20-r.nextInt(15);
            if(r.nextInt(8)==0||y%5==0&&r.nextInt(3)==0){b-=50+r.nextInt(30);g-=40;bl-=30;}
            if(x%4==0&&r.nextInt(4)==0){b-=25;g-=20;}img.setPixelRGBA(x,y,pack(255,b,g,bl));}
        Minecraft.getInstance().getTextureManager().register(boneTex,(AbstractTexture)new DynamicTexture(img));return boneTex;}
    private static ResourceLocation getMuscleTex(){
        if(muscleTex!=null)return muscleTex; muscleTex=new ResourceLocation("epicfight","dynamic/limb_muscle");
        NativeImage img=new NativeImage(NativeImage.Format.RGBA,16,16,false);Random r=new Random(41052L);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){int rv=140+r.nextInt(30),g=45+r.nextInt(25),b=40+r.nextInt(20);
            if(x%3==0){rv+=20+r.nextInt(15);g+=10;}if(y%6<1){rv+=15;g+=15;b+=10;}
            if(x%3==1&&r.nextInt(3)==0){rv-=30;g-=15;}img.setPixelRGBA(x,y,pack(255,rv,g,b));}
        Minecraft.getInstance().getTextureManager().register(muscleTex,(AbstractTexture)new DynamicTexture(img));return muscleTex;}
    private static ResourceLocation getFleshTex(){
        if(fleshTex!=null)return fleshTex; fleshTex=new ResourceLocation("epicfight","dynamic/limb_flesh");
        NativeImage img=new NativeImage(NativeImage.Format.RGBA,16,16,false);Random r=new Random(61925L);
        for(int y=0;y<16;y++)for(int x=0;x<16;x++){int rv=185+r.nextInt(25),g=80+r.nextInt(30),b=75+r.nextInt(25);
            double d=Math.sqrt(Math.pow((x-8)+r.nextGaussian()*2,2)+Math.pow((y-8)+r.nextGaussian()*2,2));
            if(d<3+r.nextDouble()*2){rv-=25;g-=10;}if(r.nextInt(12)==0){rv=160+r.nextInt(20);g=20+r.nextInt(15);b=20+r.nextInt(15);}
            if((x+y)%5==0&&r.nextInt(2)==0){rv-=10;g+=5;b+=5;}img.setPixelRGBA(x,y,pack(255,rv,g,b));}
        Minecraft.getInstance().getTextureManager().register(fleshTex,(AbstractTexture)new DynamicTexture(img));return fleshTex;}
    private static int pack(int a,int r,int g,int b){
        return(a&0xFF)<<24|(Math.max(0,Math.min(255,b))&0xFF)<<16|(Math.max(0,Math.min(255,g))&0xFF)<<8|(Math.max(0,Math.min(255,r))&0xFF);}
}
