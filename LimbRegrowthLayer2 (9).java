package net.mcreator.jujutsucraft.addon.limb;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Random;

/**
 * Tres fases pixel a pixel (TEX_SIZE=128):
 *  0.00-0.25  Hueso custom  (geometría propia, shoulder + shaft + codo + forearm + conector lateral)
 *  0.25-0.75  Carne (cubo arm/leg + textura carne, pixel reveal)
 *  0.75-1.00  Skin  (cubo arm/leg + textura jugador, pixel reveal — código idéntico al que funcionaba)
 */
public class LimbRegrowthLayer
        extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    private static final float PHASE_BONE  = 0.25f;
    private static final float PHASE_FLESH = 0.75f;
    private static final float SKIN_SCALE  = 1.002f;
    static final         float PIXEL_SCALE = 1.0f / 16.0f;
    private static final int   TEX_SIZE    = 128;

    // ── Geometría del hueso ────────────────────────────────────────────────
    // El shaft del hueso está desplazado hacia el lado EXTERIOR del brazo (lejos del torso)
    // para quedar centrado en el espacio visible del arm, NO pegado a la pared del body.
    //
    // ARM: pivot en hombro. Cubo vanilla: addBox(-2,-2,-2, 4,12,4) → x:-2..+2, y:-2..10
    //
    // RIGHT_ARM: torso está en +X local → shaft desplazado hacia -X (exterior)
    //   shaft en x:-2..0 (mitad exterior del arm), conector sale de x:0 hacia x:+2.5 (torso)
    private static final float[][] RIGHT_ARM_BONE = {
        {-2f,   -2f, -1.5f,  3f, 2f, 3f},   // shoulder   (x:-2..1)
        {-2f,    0f, -1f,    2f, 5f, 2f},   // húmero     (x:-2..0)
        {-2f,    5f, -1.5f,  3f, 1f, 3f},   // codo       (x:-2..1)
        {-2f,    6f, -1f,    2f, 4f, 2f},   // forearm    (x:-2..0)
        { 0f,  -1.5f,-0.5f,  2.5f,1f,1f},  // conector   (x:0..2.5 → hacia torso)
    };

    // LEFT_ARM: torso está en -X local → shaft desplazado hacia +X (exterior)
    //   shaft en x:0..+2, conector sale de x:0 hacia x:-2.5 (torso)
    private static final float[][] LEFT_ARM_BONE = {
        {-1f,   -2f, -1.5f,  3f, 2f, 3f},   // shoulder   (x:-1..2)
        { 0f,    0f, -1f,    2f, 5f, 2f},   // húmero     (x:0..+2)
        {-1f,    5f, -1.5f,  3f, 1f, 3f},   // codo       (x:-1..2)
        { 0f,    6f, -1f,    2f, 4f, 2f},   // forearm    (x:0..+2)
        {-2.5f,-1.5f,-0.5f,  2.5f,1f,1f},  // conector   (x:-2.5..0 → hacia torso)
    };

    // LEG: pivot en cadera. Cubo vanilla y:0..12
    private static final float[][] LEG_BONE = {
        {-1f,   0f, -1f,   2f, 5f, 2f},   // fémur
        {-1.5f, 5f, -1.5f, 3f, 1f, 3f},   // rodilla
        {-1f,   6f, -1f,   2f, 6f, 2f},   // tibia
    };

    // HEAD: cubo vanilla y:-8..0
    private static final float[][] HEAD_BONE = {
        {-3f, -7f, -3f, 6f, 6f, 6f},
    };

    // ── Texturas ──────────────────────────────────────────────────────────
    private static ResourceLocation boneTex;
    private static ResourceLocation fleshTex;

    // ── Reflexión ModelPart ───────────────────────────────────────────────
    private static Field    reflCubes, reflPolygons, reflVertices, reflNormal, reflPos, reflU, reflV;
    private static boolean  reflInit = false;

    private static void ensureReflect() {
        if (reflInit) return;
        reflInit = true;
        try {
            // cubes: primer campo List en ModelPart
            for (Field f : ModelPart.class.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true); reflCubes = f; break;
                }
            }
            if (reflCubes == null) return;
            // polygons: campo array en Cube inner class
            for (Class<?> c : ModelPart.class.getDeclaredClasses()) {
                if (c.getSimpleName().equals("Cube")) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType().isArray()) { f.setAccessible(true); reflPolygons = f; break; }
                    }
                    break;
                }
            }
            if (reflPolygons == null) return;
            Class<?> polyCls = reflPolygons.getType().getComponentType();
            for (Field f : polyCls.getDeclaredFields()) {
                if (f.getType().isArray())             { f.setAccessible(true); reflVertices = f; }
                else if (f.getType() == Vector3f.class){ f.setAccessible(true); reflNormal   = f; }
            }
            if (reflVertices == null) return;
            Class<?> vtxCls = reflVertices.getType().getComponentType();
            for (Field f : vtxCls.getDeclaredFields()) {
                if (f.getType() == Vector3f.class) { f.setAccessible(true); reflPos = f; }
                else if (f.getType() == float.class) {
                    f.setAccessible(true);
                    if (reflU == null) reflU = f; else reflV = f;
                }
            }
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static List<ModelPart.Cube> getCubes(ModelPart p) {
        try { return (List<ModelPart.Cube>) reflCubes.get(p); } catch (Throwable e) { return List.of(); }
    }
    private static Object[]  getPolygons(ModelPart.Cube c)  { try{return(Object[])reflPolygons.get(c);}catch(Throwable e){return new Object[0];} }
    private static Object[]  getVertices(Object p)           { try{return(Object[])reflVertices.get(p);}catch(Throwable e){return new Object[0];} }
    private static Vector3f  getNormal(Object p)             { try{return(Vector3f)reflNormal.get(p);}catch(Throwable e){return new Vector3f(0,1,0);} }
    private static Vector3f  getPos(Object v)                { try{return(Vector3f)reflPos.get(v);}catch(Throwable e){return new Vector3f();} }
    private static float     getU(Object v)                  { try{return reflU.getFloat(v);}catch(Throwable e){return 0;} }
    private static float     getV(Object v)                  { try{return reflV.getFloat(v);}catch(Throwable e){return 0;} }

    public LimbRegrowthLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
        ensureReflect();
    }

    // ── Render ────────────────────────────────────────────────────────────

    @Override
    public void render(PoseStack ps, MultiBufferSource buf, int light,
                       AbstractClientPlayer entity, float lS, float lSA, float pt,
                       float ait, float hY, float hP) {

        ClientLimbCache.EntityLimbSnapshot snap = ClientLimbCache.get(entity.getId());
        if (snap == null) return;
        boolean any = false;
        for (LimbType t : LimbType.values()) if (snap.getState(t) == LimbState.REVERSING) { any = true; break; }
        if (!any) return;

        @SuppressWarnings("unchecked")
        PlayerModel<AbstractClientPlayer> model = (PlayerModel<AbstractClientPlayer>) this.getParentModel();

        for (LimbType type : LimbType.values()) {
            if (snap.getState(type) != LimbState.REVERSING) continue;
            float progress = snap.getRegenProgress(type);
            if (progress <= 0f || progress >= 1.0f) continue;

            ModelPart part    = getModelPart(model, type);
            ModelPart overlay = getOverlayPart(model, type);

            // ── FASE 1: HUESO (0 → 0.25) ──────────────────────────────────
            {
                float sub = Math.min(1f, progress / PHASE_BONE);
                VertexConsumer bVc = buf.getBuffer(RenderType.entityCutoutNoCull(getOrCreateBoneTexture()));
                ps.pushPose();
                part.translateAndRotate(ps);
                renderBoneReveal(type, ps.last(), bVc, light, sub);
                ps.popPose();
            }

            // ── FASE 2: CARNE (0.25 → 0.75) — pixel reveal igual que skin ──
            if (progress > PHASE_BONE) {
                float sub = Math.min(1f, (progress - PHASE_BONE) / (PHASE_FLESH - PHASE_BONE));
                VertexConsumer fVc = buf.getBuffer(RenderType.entityCutoutNoCull(getOrCreateFleshTexture()));
                ps.pushPose();
                part.translateAndRotate(ps);
                // sin SKIN_SCALE — carne a tamaño normal del arm cube
                revealModelPart(part, ps.last(), fVc, light, sub);
                ps.popPose();
            }

            // ── FASE 3: SKIN (0.75 → 1.0) — código IDÉNTICO al que funcionaba ──
            if (progress >= PHASE_FLESH) {
                float sub = (progress - PHASE_FLESH) / (1.0f - PHASE_FLESH);
                if (sub > 0f) {
                    ResourceLocation skinTex = entity.getSkinTextureLocation();
                    VertexConsumer sVc = buf.getBuffer(RenderType.entityCutoutNoCull(skinTex));

                    ps.pushPose();
                    part.translateAndRotate(ps);
                    ps.scale(SKIN_SCALE, SKIN_SCALE, SKIN_SCALE);
                    revealModelPart(part, ps.last(), sVc, light, sub);
                    ps.popPose();

                    if (overlay != null) {
                        ps.pushPose();
                        overlay.translateAndRotate(ps);
                        ps.scale(SKIN_SCALE, SKIN_SCALE, SKIN_SCALE);
                        revealModelPart(overlay, ps.last(), sVc, light, sub);
                        ps.popPose();
                    }
                }
            }
        }
    }

    // ── Pixel reveal para ModelPart (skin y carne) ────────────────────────

    /**
     * Reveal pixel a pixel (TEX_SIZE=128) del cubo del ModelPart.
     * Código idéntico al que tenía la skin antes de los cambios de hueso.
     */
    private static void revealModelPart(ModelPart part, PoseStack.Pose pose,
                                         VertexConsumer vc, int light, float sub) {
        if (reflCubes == null) return;
        for (ModelPart.Cube cube : getCubes(part)) {
            Object[] polys = getPolygons(cube);
            if (polys.length == 0) continue;

            float yMin = Float.MAX_VALUE, yMax = -Float.MAX_VALUE;
            for (Object p : polys)
                for (Object v : getVertices(p)) { float y = getPos(v).y(); if(y<yMin)yMin=y; if(y>yMax)yMax=y; }
            float yRange = Math.max(0.001f, yMax - yMin);

            for (Object poly : polys) {
                Object[] verts = getVertices(poly);
                if (verts.length != 4) continue;
                Vector3f normal = getNormal(poly);

                Vector3f[] pos = new Vector3f[4]; float[] us = new float[4], vs = new float[4];
                for (int i = 0; i < 4; i++) { pos[i]=getPos(verts[i]); us[i]=getU(verts[i]); vs[i]=getV(verts[i]); }

                float uMin=mn(us), uMax=mx(us), vMin=mn(vs), vMax=mx(vs);
                int nU=Math.max(1,Math.round((uMax-uMin)*TEX_SIZE));
                int nV=Math.max(1,Math.round((vMax-vMin)*TEX_SIZE));
                float stU=(uMax-uMin)/nU, stV=(vMax-vMin)/nV;

                for (int pi=0; pi<nU; pi++) for (int pj=0; pj<nV; pj++) {
                    float u0=uMin+pi*stU, u1=u0+stU, v0=vMin+pj*stV, v1=v0+stV;
                    float uC=(u0+u1)*.5f, vC=(v0+v1)*.5f;

                    Vector3f c = blerp(pos, us, vs, uC, vC, uMin, uMax, vMin, vMax);
                    float yN  = Math.max(0f, Math.min(1f, (c.y()-yMin)/yRange));
                    float noise = (noise8((int)(uC*8f),(int)(vC*8f))+1f)*.5f;
                    if (sub <= yN*.82f + noise*.18f) continue;

                    Vector3f c00=blerp(pos,us,vs,u0,v0,uMin,uMax,vMin,vMax);
                    Vector3f c10=blerp(pos,us,vs,u1,v0,uMin,uMax,vMin,vMax);
                    Vector3f c11=blerp(pos,us,vs,u1,v1,uMin,uMax,vMin,vMax);
                    Vector3f c01=blerp(pos,us,vs,u0,v1,uMin,uMax,vMin,vMax);

                    pxl(pose,vc,c00,u0,v0,normal,light); pxl(pose,vc,c10,u1,v0,normal,light);
                    pxl(pose,vc,c11,u1,v1,normal,light); pxl(pose,vc,c01,u0,v1,normal,light);
                }
            }
        }
    }

    private static float mn(float[] a){return Math.min(Math.min(a[0],a[1]),Math.min(a[2],a[3]));}
    private static float mx(float[] a){return Math.max(Math.max(a[0],a[1]),Math.max(a[2],a[3]));}

    private static Vector3f blerp(Vector3f[] pos, float[] us, float[] vs,
                                   float tu, float tv,
                                   float uMin, float uMax, float vMin, float vMax) {
        float uM=(uMin+uMax)*.5f, vM=(vMin+vMax)*.5f;
        Vector3f tl=pos[0],tr=pos[0],bl=pos[0],br=pos[0];
        for (int i=0;i<4;i++){
            boolean L=us[i]<=uM, T=vs[i]<=vM;
            if(L&&T)tl=pos[i]; if(!L&&T)tr=pos[i]; if(L&&!T)bl=pos[i]; if(!L&&!T)br=pos[i];
        }
        float s=(uMax>uMin)?(tu-uMin)/(uMax-uMin):.5f, t=(vMax>vMin)?(tv-vMin)/(vMax-vMin):.5f;
        float is=1f-s, it=1f-t;
        return new Vector3f(tl.x()*is*it+tr.x()*s*it+br.x()*s*t+bl.x()*is*t,
                            tl.y()*is*it+tr.y()*s*it+br.y()*s*t+bl.y()*is*t,
                            tl.z()*is*it+tr.z()*s*it+br.z()*s*t+bl.z()*is*t);
    }

    private static void pxl(PoseStack.Pose pose, VertexConsumer vc,
                              Vector3f p, float u, float v, Vector3f n, int light) {
        vc.vertex(pose.pose(), p.x()*PIXEL_SCALE, p.y()*PIXEL_SCALE, p.z()*PIXEL_SCALE)
          .color(1f,1f,1f,1f).uv(u,v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
          .normal(pose.normal(), n.x(), n.y(), n.z()).endVertex();
    }

    // ── Reveal de hueso (geometría custom) ───────────────────────────────

    private static float noise8(int cx, int cy) {
        int h = cx*374761393 ^ cy*1234567891; h^=(h>>>13); h*=1540483477; h^=(h>>>15);
        return (h & 0xFFFF) / 32767.5f - 1f;
    }

    private static float[][] getBoneCubes(LimbType t) {
        return switch (t) {
            case RIGHT_ARM           -> RIGHT_ARM_BONE;
            case LEFT_ARM            -> LEFT_ARM_BONE;
            case RIGHT_LEG, LEFT_LEG -> LEG_BONE;
            case HEAD                -> HEAD_BONE;
        };
    }


    private static void renderBoneReveal(LimbType type, PoseStack.Pose pose,
                                          VertexConsumer vc, int light, float sub) {
        float[][] defs = getBoneCubes(type);
        float gYMin=Float.MAX_VALUE, gYMax=-Float.MAX_VALUE;
        for (float[] b : defs) { if(b[1]<gYMin)gYMin=b[1]; if(b[1]+b[4]>gYMax)gYMax=b[1]+b[4]; }
        float yR = Math.max(0.001f, gYMax-gYMin);
        for (float[] b : defs) boneCube(b, pose, vc, light, sub, gYMin, yR);
    }

    private static void boneCube(float[] b, PoseStack.Pose pose, VertexConsumer vc,
                                  int light, float sub, float gYMin, float yR) {
        float ox=b[0],oy=b[1],oz=b[2],w=b[3],h=b[4],d=b[5];
        int nW=Math.max(1,Math.round((w/16f)*TEX_SIZE));
        int nH=Math.max(1,Math.round((h/16f)*TEX_SIZE));
        int nD=Math.max(1,Math.round((d/16f)*TEX_SIZE));
        float sW=w/nW,sH=h/nH,sD=d/nD, uW=(w/16f)/nW,uH=(h/16f)/nH,uD=(d/16f)/nD;

        for(int pi=0;pi<nW;pi++)for(int pj=0;pj<nH;pj++){   // Front z=oz+d
            float x0=ox+pi*sW,x1=x0+sW,y0=oy+pj*sH,y1=y0+sH;
            if(!bShow((y0+y1)*.5f,gYMin,yR,pi,pj,sub))continue;
            float u0=pi*uW,v0=pj*uH; bQ(pose,vc,x0,y0,oz+d,x1,y0,oz+d,x1,y1,oz+d,x0,y1,oz+d,u0,v0,u0+uW,v0+uH,0,0,1,light);}
        for(int pi=0;pi<nW;pi++)for(int pj=0;pj<nH;pj++){   // Back z=oz
            float x0=ox+pi*sW,x1=x0+sW,y0=oy+pj*sH,y1=y0+sH;
            if(!bShow((y0+y1)*.5f,gYMin,yR,pi,pj,sub))continue;
            float u0=pi*uW,v0=pj*uH; bQ(pose,vc,x1,y0,oz,x0,y0,oz,x0,y1,oz,x1,y1,oz,u0,v0,u0+uW,v0+uH,0,0,-1,light);}
        for(int pi=0;pi<nD;pi++)for(int pj=0;pj<nH;pj++){   // Left x=ox
            float z0=oz+pi*sD,z1=z0+sD,y0=oy+pj*sH,y1=y0+sH;
            if(!bShow((y0+y1)*.5f,gYMin,yR,pi,pj,sub))continue;
            float u0=pi*uD,v0=pj*uH; bQ(pose,vc,ox,y0,z1,ox,y0,z0,ox,y1,z0,ox,y1,z1,u0,v0,u0+uD,v0+uH,-1,0,0,light);}
        for(int pi=0;pi<nD;pi++)for(int pj=0;pj<nH;pj++){   // Right x=ox+w
            float z0=oz+pi*sD,z1=z0+sD,y0=oy+pj*sH,y1=y0+sH;
            if(!bShow((y0+y1)*.5f,gYMin,yR,pi,pj,sub))continue;
            float u0=pi*uD,v0=pj*uH; bQ(pose,vc,ox+w,y0,z0,ox+w,y0,z1,ox+w,y1,z1,ox+w,y1,z0,u0,v0,u0+uD,v0+uH,1,0,0,light);}
        for(int pi=0;pi<nW;pi++)for(int pj=0;pj<nD;pj++){   // Top y=oy
            float x0=ox+pi*sW,x1=x0+sW,z0=oz+pj*sD,z1=z0+sD;
            if(!bShow(oy,gYMin,yR,pi,pj,sub))continue;
            float u0=pi*uW,v0=pj*uD; bQ(pose,vc,x0,oy,z0,x1,oy,z0,x1,oy,z1,x0,oy,z1,u0,v0,u0+uW,v0+uD,0,-1,0,light);}
        for(int pi=0;pi<nW;pi++)for(int pj=0;pj<nD;pj++){   // Bottom y=oy+h
            float x0=ox+pi*sW,x1=x0+sW,z0=oz+pj*sD,z1=z0+sD;
            if(!bShow(oy+h,gYMin,yR,pi,pj,sub))continue;
            float u0=pi*uW,v0=pj*uD; bQ(pose,vc,x0,oy+h,z1,x1,oy+h,z1,x1,oy+h,z0,x0,oy+h,z0,u0,v0,u0+uW,v0+uD,0,1,0,light);}
    }

    private static boolean bShow(float yC, float gYMin, float yR, int pi, int pj, float sub) {
        float yN = Math.max(0f,Math.min(1f,(yC-gYMin)/yR));
        float noise = (noise8(pi,pj)+1f)*.5f;
        return sub > yN*.82f + noise*.18f;
    }

    private static void bQ(PoseStack.Pose pose, VertexConsumer vc,
                            float x0,float y0,float z0, float x1,float y1,float z1,
                            float x2,float y2,float z2, float x3,float y3,float z3,
                            float u0,float v0, float u1,float v1,
                            float nx,float ny,float nz, int light) {
        bV(pose,vc,x0,y0,z0,u0,v0,nx,ny,nz,light); bV(pose,vc,x1,y1,z1,u1,v0,nx,ny,nz,light);
        bV(pose,vc,x2,y2,z2,u1,v1,nx,ny,nz,light); bV(pose,vc,x3,y3,z3,u0,v1,nx,ny,nz,light);
    }

    private static void bV(PoseStack.Pose pose, VertexConsumer vc,
                            float x,float y,float z,float u,float v,
                            float nx,float ny,float nz,int light) {
        vc.vertex(pose.pose(),x*PIXEL_SCALE,y*PIXEL_SCALE,z*PIXEL_SCALE)
          .color(1f,1f,1f,1f).uv(u,v).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
          .normal(pose.normal(),nx,ny,nz).endVertex();
    }

    // ── Helpers ModelPart ─────────────────────────────────────────────────

    private static ModelPart getModelPart(PlayerModel<?> m, LimbType t){
        return switch(t){case LEFT_ARM->m.leftArm;case RIGHT_ARM->m.rightArm;
            case LEFT_LEG->m.leftLeg;case RIGHT_LEG->m.rightLeg;case HEAD->m.head;};}
    private static ModelPart getOverlayPart(PlayerModel<?> m, LimbType t){
        return switch(t){case LEFT_ARM->m.leftSleeve;case RIGHT_ARM->m.rightSleeve;
            case LEFT_LEG->m.leftPants;case RIGHT_LEG->m.rightPants;case HEAD->null;};}

    // ── Texturas procedurales ─────────────────────────────────────────────

    private static ResourceLocation getOrCreateBoneTexture() {
        if (boneTex==null){boneTex=new ResourceLocation("jjkblueredpurple","dynamic/bone");
            NativeImage img=new NativeImage(NativeImage.Format.RGBA,16,16,false);
            Random r=new Random(45214L);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++){
                int b=210+r.nextInt(30),g=b-5-r.nextInt(10),bl=b-20-r.nextInt(15);
                if(r.nextInt(8)==0||y%5==0&&r.nextInt(3)==0){b-=50+r.nextInt(30);g-=40;bl-=30;}
                if(x%4==0&&r.nextInt(4)==0){b-=25;g-=20;}
                img.setPixelRGBA(x,y,pk(255,clamp(b),clamp(g),clamp(bl)));}
            Minecraft.getInstance().getTextureManager().register(boneTex,(AbstractTexture)new DynamicTexture(img));}
        return boneTex;
    }
    private static ResourceLocation getOrCreateFleshTexture() {
        if (fleshTex==null){fleshTex=new ResourceLocation("jjkblueredpurple","dynamic/flesh");
            NativeImage img=new NativeImage(NativeImage.Format.RGBA,16,16,false);
            Random r=new Random(61925L);
            for(int y=0;y<16;y++)for(int x=0;x<16;x++){
                int rr=185+r.nextInt(25),g=80+r.nextInt(30),b=75+r.nextInt(25);
                double d=Math.sqrt(Math.pow((x-8)+r.nextGaussian()*2,2)+Math.pow((y-8)+r.nextGaussian()*2,2));
                if(d<3+r.nextDouble()*2){rr-=25;g-=10;}
                if(r.nextInt(12)==0){rr=160+r.nextInt(20);g=20+r.nextInt(15);b=20+r.nextInt(15);}
                if((x+y)%5==0&&r.nextInt(2)==0){rr-=10;g+=5;b+=5;}
                img.setPixelRGBA(x,y,pk(255,clamp(rr),clamp(g),clamp(b)));}
            Minecraft.getInstance().getTextureManager().register(fleshTex,(AbstractTexture)new DynamicTexture(img));}
        return fleshTex;
    }
    private static int pk(int a,int r,int g,int b){return(a&0xFF)<<24|(b&0xFF)<<16|(g&0xFF)<<8|r&0xFF;}
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
}
