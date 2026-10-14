package com.PinkCats.bandwidthoptimizer.integration.neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Only the two immutable vanilla scalar packets below have a proven size bound.
 * Optional/new protocol shapes and replaced codecs keep the native splitter. */
public final class BoundedPacketSplitterCompat {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("bandwidthoptimizer.compat.boundedSplitterEnabled", "true"));
    private static final String MOTION = "net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket";
    private static final String HEAD = "net.minecraft.network.protocol.game.ClientboundRotateHeadPacket";
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        @Override protected Access computeValue(Class<?> packetClass) {
            if (!packetClass.getName().equals(MOTION) && !packetClass.getName().equals(HEAD)) return Access.NONE;
            try {
                ClassLoader loader = packetClass.getClassLoader();
                Object vanilla = packetClass.getField("STREAM_CODEC").get(null);
                Class<?> stream = Class.forName("net.minecraft.network.codec.StreamCodec", false, loader);
                if (!hasProvenNativeCodec(packetClass, vanilla)) return Access.NONE;
                Object wrapper = stream.getMethod("mapStream", Function.class).invoke(vanilla, Function.identity());
                Field inner = null, decorator = null;
                for (Field field : wrapper.getClass().getDeclaredFields()) {
                    if (stream.isAssignableFrom(field.getType())) inner = field;
                    else if (Function.class.isAssignableFrom(field.getType())) decorator = field;
                }
                if (inner == null || decorator == null) return Access.NONE;
                Class<?> registry = Class.forName("net.minecraft.core.RegistryAccess", false, loader);
                Class<?> connection = Class.forName("net.neoforged.neoforge.network.connection.ConnectionType", false, loader);
                Object other = connection.getField("OTHER").get(null);
                Class<?> buffer = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf", false, loader);
                Object factory = buffer.getMethod("decorator", registry, connection).invoke(null, registry.getField("EMPTY").get(null), other);
                Class<?> flow = Class.forName("net.minecraft.network.protocol.PacketFlow", false, loader);
                Class<?> builder = Class.forName("net.minecraft.network.protocol.ProtocolCodecBuilder", false, loader);
                Object dispatch = builder.getMethod("build").invoke(builder.getConstructor(flow).newInstance(flow.getField("CLIENTBOUND").get(null)));
                Field getter = accessible(dispatch.getClass(), "typeGetter");
                Field byId = accessible(dispatch.getClass(), "byId");
                Field toId = accessible(dispatch.getClass(), "toId");
                Class<?> entry = Class.forName("net.minecraft.network.codec.IdDispatchCodec$Entry", false, loader);
                inner.setAccessible(true); decorator.setAccessible(true);
                return new Access(vanilla, wrapper.getClass(), inner, decorator, factory.getClass(), dispatch.getClass(),
                        getter, getter.get(dispatch).getClass(), byId, toId, accessible(entry, "serializer"));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return Access.NONE; }
        }
    };
    private BoundedPacketSplitterCompat() {}

    public static boolean canSkip(Object packet, Object codec) {
        if (!ENABLED || packet == null || codec == null) return false;
        Access a = ACCESS.get(packet.getClass());
        if (a == Access.NONE || codec.getClass() != a.dispatchClass) return false;
        try {
            Object getter = a.getter.get(codec);
            if (getter.getClass() != a.getterClass) return false;
            @SuppressWarnings("unchecked") Object type = ((Function<Object, ?>) getter).apply(packet);
            Object id = ((Map<?, ?>) a.toId.get(codec)).get(type);
            if (!(id instanceof Integer index) || index < 0) return false;
            List<?> entries = (List<?>) a.byId.get(codec);
            if (index >= entries.size()) return false;
            Object wrapper = a.serializer.get(entries.get(index));
            return wrapper != null && wrapper.getClass() == a.wrapperClass && a.inner.get(wrapper) == a.vanilla
                    && a.decorator.get(wrapper).getClass() == a.decoratorClass;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return false; }
    }

    private static Field accessible(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    // Checking only a public STREAM_CODEC reference would also trust a mod that
    // replaces that static field. Check the native codec's captured call sites.
    static boolean hasProvenNativeCodec(Class<?> packetClass, Object codec) throws ReflectiveOperationException {
        ClassLoader loader=packetClass.getClassLoader();
        Class<?> stream=Class.forName("net.minecraft.network.codec.StreamCodec",false,loader);
        if(codec.getClass().getEnclosingClass()!=stream)return false;
        if(!packetClass.isRecord())return nativeMemberCodec(codec,packetClass);
        if(!packetClass.getName().equals(MOTION))return false;
        Object varInt=Class.forName("net.minecraft.network.codec.ByteBufCodecs",false,loader).getField("VAR_INT").get(null);
        Object vector=Class.forName("net.minecraft.world.phys.Vec3",false,loader).getField("LP_STREAM_CODEC").get(null);
        if(varInt.getClass().getEnclosingClass()!=Class.forName("net.minecraft.network.codec.ByteBufCodecs",false,loader)
                || vector.getClass().getEnclosingClass()!=stream
                || !nativeMemberCodec(vector,Class.forName("net.minecraft.network.LpVec3",false,loader)))return false;
        int codecs=0,functions=0;
        for(Field field:codec.getClass().getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            field.setAccessible(true);Object value=field.get(codec);
            if(stream.isAssignableFrom(field.getType())) {if(value!=varInt && value!=vector)return false;codecs++;}
            else if(field.getType().getName().startsWith("java.util.function.")) {if(value==null || value.getClass().getNestHost()!=packetClass)return false;functions++;}
            else return false;
        }
        // 26.x motion: 5-byte entity ID + LpVec3's 6 bytes/5-byte continuation
        // + 5-byte dispatch ID <= 21 bytes, including NaN/infinity sanitization.
        return codecs==2 && functions==3;
    }

    private static boolean nativeMemberCodec(Object codec,Class<?> owner) throws IllegalAccessException {
        int functions=0;
        for(Field field:codec.getClass().getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(field.getModifiers()))continue;
            String name=field.getType().getName();
            if(!name.equals("net.minecraft.network.codec.StreamMemberEncoder") && !name.equals("net.minecraft.network.codec.StreamEncoder")
                    && !name.equals("net.minecraft.network.codec.StreamDecoder"))return false;
            field.setAccessible(true);Object value=field.get(codec);
            if(value==null || value.getClass().getNestHost()!=owner)return false;
            functions++;
        }
        return functions==2;
    }
    private record Access(Object vanilla, Class<?> wrapperClass, Field inner, Field decorator, Class<?> decoratorClass,
                          Class<?> dispatchClass, Field getter, Class<?> getterClass, Field byId, Field toId, Field serializer) {
        static final Access NONE = new Access(null, null, null, null, null, null, null, null, null, null, null);
    }
}
