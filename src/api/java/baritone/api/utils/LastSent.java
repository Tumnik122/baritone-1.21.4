package baritone.api.utils;

/**
 * Stan gracza TAK, JAK WIDZI GO SERWER (ostatni wysłany pakiet pozycji/ruchu).
 * Implementowany mixinem na LocalPlayer (pola: xLast, yLast, zLast, yRotLast, xRotLast).
 * To względem tego stanu serwer/Grim waliduje pakiet kopania — nie względem stanu "po ticku" klienta.
 */
public interface LastSent {
    double mine$lastX();

    double mine$lastY();

    double mine$lastZ();

    float mine$lastYaw();

    float mine$lastPitch();
}
