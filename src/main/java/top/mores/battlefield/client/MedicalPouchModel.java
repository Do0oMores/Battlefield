package top.mores.battlefield.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Supplied 8x6x6 pixel stepped textile roll; +Z is the front red-cross face. */
public final class MedicalPouchModel {
    public static final ResourceLocation TEXTURE = new ResourceLocation("battlefield", "textures/entity/medical_pouch.png");
    private static final int FRONT=0, BACK=1, TOP=2, BOTTOM=3, END=4, BAND=5, TRIM=6;
    private MedicalPouchModel() {}
    public static void render(PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        try {
            pose.scale(1F/16F,1F/16F,1F/16F);
            VertexConsumer v = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
            cuboid(pose,v,-3.6F,-2.6F,-2.5F,3.6F,2.6F,2.5F,FRONT,BACK,TOP,BOTTOM,END,END,light);
            cuboid(pose,v,-4,-2.35F,-2.25F,-3.55F,2.35F,2.25F,TRIM,TRIM,TRIM,TRIM,END,END,light);
            cuboid(pose,v,3.55F,-2.35F,-2.25F,4,2.35F,2.25F,TRIM,TRIM,TRIM,TRIM,END,END,light);
            for (float x : new float[]{-2.4F,2.4F})
                cuboid(pose,v,x-0.35F,-2.72F,-2.60F,x+0.35F,2.72F,2.60F,BAND,BAND,BAND,BAND,BAND,BAND,light);
        } finally { pose.popPose(); }
    }
    // Directions: front = +Z, back = -Z, top = +Y, bottom = -Y, left = -X, right = +X.
    private static void cuboid(PoseStack p, VertexConsumer v, float x0,float y0,float z0,
                               float x1,float y1,float z1,
                               int f,int b,int top,int bottom,int left,int right,int light) {
        quad(p,v, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1, 0,0,1,f,light);
        quad(p,v, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0, 0,0,-1,b,light);
        quad(p,v, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0, 0,1,0,top,light);
        quad(p,v, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1, 0,-1,0,bottom,light);
        quad(p,v, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0, -1,0,0,left,light);
        quad(p,v, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1, 1,0,0,right,light);
    }
    private static void quad(PoseStack p,VertexConsumer out,
                             float ax,float ay,float az,float bx,float by,float bz,
                             float cx,float cy,float cz,float dx,float dy,float dz,
                             float nx,float ny,float nz,int tile,int light){
        int tx=(tile%4)*64, ty=(tile/4)*64;
        float u0=(tx+1)/256F,v0=(ty+1)/256F,u1=(tx+63)/256F,v1=(ty+63)/256F;
        PoseStack.Pose transform=p.last(); Matrix4f mat=transform.pose(); Matrix3f normal=transform.normal();
        vertex(out,mat,normal,ax,ay,az,u0,v1,light,nx,ny,nz);
        vertex(out,mat,normal,bx,by,bz,u1,v1,light,nx,ny,nz);
        vertex(out,mat,normal,cx,cy,cz,u1,v0,light,nx,ny,nz);
        vertex(out,mat,normal,dx,dy,dz,u0,v0,light,nx,ny,nz);
    }
    private static void vertex(VertexConsumer v,Matrix4f mat,Matrix3f normal,
                               float x,float y,float z,float u,float t,int light,float nx,float ny,float nz){
        v.vertex(mat,x,y,z).color(255,255,255,255).uv(u,t)
         .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(normal,nx,ny,nz).endVertex();
    }
}
