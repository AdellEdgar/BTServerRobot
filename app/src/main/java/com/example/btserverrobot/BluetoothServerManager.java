package com.example.btserverrobot;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/*
 * Hilo del servidor Bluetooth.
 *   1. Abre un "puesto de escucha" (BluetoothServerSocket) con el UUID SPP.
 *   2. Espera a que se conecte un cliente (accept, bloqueante).
 *   3. Lee lo que envía el cliente (read, bloqueante) y lo pasa a la pantalla con el Handler.
 *   4. Si el cliente se desconecta, vuelve a esperar a otro.
 */
public class BluetoothServerManager extends Thread {

    private static final String TAG = "Servidor";

    // Mismo UUID SPP que usa el cliente: así se "encuentran"
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    // Nombre del servicio. Es interno: no es el nombre del móvil que ve el cliente
    private static final String NAME = "BTROBOTSERVERSIM";

    // Tipos de mensaje para el Handler (lo que va en msg.arg1)
    public static final int MSG_DATOS = 0;    // datos recibidos del cliente
    public static final int MSG_ESTADO = 1;   // estado del socket

    private BluetoothServerSocket bluetoothServerSocket;   // puesto de escucha
    private BluetoothSocket socket;                        // conexión con el cliente
    private InputStream inputStream;                       // RECIBIR del cliente
    private OutputStream outputStream;                     // ENVIAR al cliente
    private final Handler handlerNetworkExecutorResult;    // "buzón" de la MainActivity

    // volatile: la cambia el hilo principal (botón CLOSE) y la lee este hilo
    private volatile boolean cerrar = false;

    // ============================================================
    //  CONSTRUCTOR (paso 13). Se llama IGUAL que la clase
    // ============================================================
    @SuppressLint("MissingPermission")
    public BluetoothServerManager(BluetoothAdapter bluetoothAdapter, Handler handler) {
        this.handlerNetworkExecutorResult = handler;
        try {
            // Abre el puesto de escucha. Todavía NO espera: eso lo hace accept() en run()
            bluetoothServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME, MY_UUID);
        } catch (IOException e) {
            Log.e(TAG, "Error al crear el server socket", e);
        }
    }

    // ============================================================
    //  MENSAJES A LA PANTALLA (paso 14)
    //  Este hilo NO puede tocar la interfaz: envía un Message al Handler
    // ============================================================
    private void showDisplayMessage(String texto) {
        Message msg = new Message();
        msg.arg1 = MSG_DATOS;
        msg.obj = texto;
        handlerNetworkExecutorResult.sendMessage(msg);
    }

    private void showSocketStateMessage(String texto) {
        Message msg = new Message();
        msg.arg1 = MSG_ESTADO;
        msg.obj = texto;
        handlerNetworkExecutorResult.sendMessage(msg);
    }

    // ============================================================
    //  RUN (paso 16): lo que hace el hilo al arrancar con start()
    // ============================================================
    @SuppressLint("MissingPermission")
    @Override
    public void run() {
        if (bluetoothServerSocket == null) {
            showSocketStateMessage("SOCKET ERROR");
            return;
        }

        while (!cerrar) {
            try {
                showSocketStateMessage("WAITING FOR CONNECTION");
                socket = bluetoothServerSocket.accept();   // BLOQUEA hasta que se conecte un cliente

                String mac = socket.getRemoteDevice().getAddress();
                String nombre = socket.getRemoteDevice().getName();
                showSocketStateMessage("CONNECTED: " + nombre + " (" + mac + ")");

            } catch (IOException e) {
                // accept() falla al cerrar el server socket desde cancelar()
                if (!cerrar) showSocketStateMessage("SOCKET ERROR: " + e.getMessage());
                break;
            }

            manageConnectedSocket(socket);   // atender al cliente hasta que se vaya
        }
        Log.d(TAG, "Hilo del servidor terminado");
    }

    // ============================================================
    //  ATENDER AL CLIENTE CONECTADO (paso 17, corregido)
    // ============================================================
    private void manageConnectedSocket(BluetoothSocket socket) {
        try {
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
            byte[] buffer = new byte[1024];

            while (!cerrar) {
                int n = inputStream.read(buffer);    // BLOQUEA hasta que llegan datos
                if (n == -1) break;                  // -1 = el cliente ha cerrado la conexión

                // Solo los n bytes leídos (no los 1024) y sin espacios ni \n en los extremos
                String cadena = new String(buffer, 0, n).trim();

                if (cadena.equalsIgnoreCase("exit")) break;
                showDisplayMessage(cadena);
            }
        } catch (IOException e) {
            Log.d(TAG, "Conexión terminada: " + e.getMessage());   // cliente desconectado o CLOSE
        } finally {
            cerrarSocketCliente();
            if (!cerrar) showSocketStateMessage("CLIENT DISCONNECTED");
        }
    }

    // ============================================================
    //  ENVIAR AL CLIENTE (no viene en las diapositivas)
    // ============================================================
    public boolean enviar(String texto) {
        if (outputStream == null) return false;
        try {
            outputStream.write((texto + "\n").getBytes());
            outputStream.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error al enviar", e);
            return false;
        }
    }

    // ============================================================
    //  CERRAR TODO (no viene en las diapositivas)
    //  Cerrar los sockets desbloquea accept() y read() con una IOException
    // ============================================================
    public void cancelar() {
        cerrar = true;
        cerrarSocketCliente();
        try {
            if (bluetoothServerSocket != null) bluetoothServerSocket.close();
        } catch (IOException ignored) { }
    }

    private void cerrarSocketCliente() {
        try {
            if (socket != null) socket.close();   // cierra también los streams
        } catch (IOException ignored) { }
        socket = null;
        inputStream = null;
        outputStream = null;
    }
}
