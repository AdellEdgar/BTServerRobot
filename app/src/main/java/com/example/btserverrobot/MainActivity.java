package com.example.btserverrobot;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

public class MainActivity extends AppCompatActivity {

    // Etiqueta para el Logcat (filtro: tag:Servidor)
    private static final String TAG = "Servidor";

    // =================================================================
    //  INTERFAZ
    // =================================================================
    TextView BT_Name_TextView;               // nombre del Bluetooth de este móvil
    TextView BT_MAC_TextView;                // MAC del Bluetooth de este móvil
    Button acceptConnectionButton;           // empezar a esperar conexiones
    Button closeConnectionButton;            // cerrar la conexión
    TextView BT_ConnectionState_TextView;    // estado: NOT CONNECTED, WAITING..., CONNECTED
    TextView receivedEditText;               // lo que llega del cliente
    EditText sendTextEditText;               // lo que escribimos para enviar
    Button sendTextButton;                   // enviar al cliente

    // =================================================================
    //  BLUETOOTH
    // =================================================================
    private static final int REQUEST_BT_PERMISSIONS = 777;   // código de la petición de permisos
    BluetoothAdapter mBluetoothAdapter;                       // el Bluetooth de ESTE móvil
    private ActivityResultLauncher<Intent> enableBtLauncher;  // aviso "¿Activar el Bluetooth?"
    private BluetoothServerManager btServerManager;   // el hilo del servidor
    private Handler handlerNetworkExecutorResult;     // recibe los mensajes del hilo
    private ActivityResultLauncher<Intent> discoverableLauncher;   // aviso "¿Hacer visible?"


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int margen = (int) (16 * getResources().getDisplayMetrics().density);   // 16dp en píxeles
            v.setPadding(systemBars.left + margen, systemBars.top + margen,
                    systemBars.right + margen, systemBars.bottom + margen);
            return insets;
        });

        // ----- 1. Enlazar las variables con el XML  -----
        BT_Name_TextView            = findViewById(R.id.BT_Name_TextView);
        BT_MAC_TextView             = findViewById(R.id.BT_MAC_TextView);
        acceptConnectionButton      = findViewById(R.id.acceptConnectionButton);
        closeConnectionButton       = findViewById(R.id.closeConnectionButton);
        BT_ConnectionState_TextView = findViewById(R.id.BT_ConnectionState_TextView);
        receivedEditText            = findViewById(R.id.receivedEditText);
        sendTextEditText            = findViewById(R.id.sendTextEditText);
        sendTextButton              = findViewById(R.id.sendTextButton);

        // ----- 2. Estado inicial -----
        estadoNoConectado();

        // ----- 3. Preparar el lanzador para encender el Bluetooth -----
        // Se registra en onCreate, ANTES de que se muestre la pantalla
        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show();
                        showBT_MAC_NAME();
                    } else {
                        Toast.makeText(this, "Bluetooth NOT enabled", Toast.LENGTH_SHORT).show();
                        acceptConnectionButton.setEnabled(false);   // sin Bluetooth no se puede aceptar
                    }
                }
        );

        // ----- Lanzador para hacer visible el móvil -----
        // OJO: aquí el resultCode NO es RESULT_OK. Es el número de segundos
        // que será visible, o RESULT_CANCELED si el usuario dice que no
        discoverableLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_CANCELED) {
                        Toast.makeText(this, "El móvil NO será visible", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.d(TAG, "Visible durante " + result.getResultCode() + " s");
                    }
                }
        );

        // ----- Handler: el "buzón" donde el hilo deja sus mensajes -----
        // Looper.getMainLooper() → handleMessage se ejecuta en el hilo PRINCIPAL,
        // el único que puede tocar la interfaz
        handlerNetworkExecutorResult = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                String message = (String) msg.obj;
                if (msg.arg1 == BluetoothServerManager.MSG_DATOS) {
                    receivedEditText.append(message + "\n");            // datos del cliente
                } else if (msg.arg1 == BluetoothServerManager.MSG_ESTADO) {
                    BT_ConnectionState_TextView.setText(message);       // estado del socket
                }
            }
        };

        // ----- Botones -----
        acceptConnectionButton.setOnClickListener(v -> acceptConnection());
        closeConnectionButton.setOnClickListener(v -> closeConnection());
        sendTextButton.setOnClickListener(v -> sendText());

        // ----- 4. Obtener el Bluetooth del móvil -----
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Device does not support Bluetooth", Toast.LENGTH_SHORT).show();
            acceptConnectionButton.setEnabled(false);
            return;
        }

        // ----- 5. Comprobar permisos y si está encendido -----
        comprobarBluetooth();
    }

    // =================================================================
    //  BOTONES
    // =================================================================

    /*
     * ACCEPT: crea el hilo del servidor y lo arranca.
     * Un Thread solo se puede arrancar UNA vez, así que se crea uno nuevo en cada ACCEPT.
     */
    private void acceptConnection() {
        if (btServerManager == null) {
            btServerManager = new BluetoothServerManager(mBluetoothAdapter, handlerNetworkExecutorResult);
            btServerManager.start();   // start(), NO run()
            Log.d(TAG, "Hilo del servidor arrancado");
        }

        // Pedir que el móvil sea visible 300 segundos (5 minutos)
        Intent discoverable = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
        discoverable.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
        discoverableLauncher.launch(discoverable);

        estadoConectado();
    }

    /*
     * CLOSE: cierra los sockets (desbloquea accept/read) y el hilo termina.
     */
    private void closeConnection() {
        if (btServerManager != null) {
            btServerManager.cancelar();
            btServerManager = null;   // el siguiente ACCEPT creará un hilo nuevo
        }
        estadoNoConectado();
    }

    /*
     * SEND: envía el texto escrito al cliente. No viene en las diapositivas.
     */
    private void sendText() {
        String texto = sendTextEditText.getText().toString().trim();
        if (texto.isEmpty()) return;

        if (btServerManager != null && btServerManager.enviar(texto)) {
            receivedEditText.append("> " + texto + "\n");   // "> " = lo que envía el servidor
            sendTextEditText.setText("");                    // vaciar la caja
        } else {
            Toast.makeText(this, "No hay ningún cliente conectado", Toast.LENGTH_SHORT).show();
        }
    }

    // =================================================================
    //  PERMISOS Y ESTADO DEL BLUETOOTH
    // =================================================================

    /*
     * 1. ¿Tenemos CONNECT y ADVERTISE? Si no, se piden los dos a la vez.
     * 2. ¿Está encendido? Si no, se pide al usuario que lo encienda.
     */
    private void comprobarBluetooth() {
        if (!tienePermisos()) {
            Log.d(TAG, "Faltan permisos, los pido");
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE
                    },
                    REQUEST_BT_PERMISSIONS);
            return;   // se continúa en onRequestPermissionsResult
        }

        if (mBluetoothAdapter.isEnabled()) {
            showBT_MAC_NAME();
        } else {
            Log.d(TAG, "Bluetooth apagado, pido encenderlo");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtLauncher.launch(enableBtIntent);   // respuesta → lambda del enableBtLauncher
        }
    }

    // ¿Están concedidos los dos permisos?
    private boolean tienePermisos() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /*
     * Respuesta al aviso de permisos. Como se piden dos, grantResults tiene dos
     * resultados: hay que comprobar que estén concedidos TODOS.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_BT_PERMISSIONS) {
            boolean todosConcedidos = grantResults.length > 0;
            for (int resultado : grantResults) {
                if (resultado != PackageManager.PERMISSION_GRANTED) {
                    todosConcedidos = false;
                }
            }

            if (todosConcedidos) {
                Log.d(TAG, "Permisos concedidos");
                comprobarBluetooth();   // volver a empezar, ahora con permisos
            } else {
                Log.w(TAG, "Permisos denegados");
                Toast.makeText(this, "Permisos de Bluetooth denegados", Toast.LENGTH_LONG).show();
                acceptConnectionButton.setEnabled(false);
            }
        }
    }

    // =================================================================
    //  NOMBRE Y MAC DEL SERVIDOR
    // =================================================================

    @SuppressLint({"MissingPermission", "HardwareIds"})
    private void showBT_MAC_NAME() {
        String BT_name = mBluetoothAdapter.getName();
        String BT_MAC = mBluetoothAdapter.getAddress();   // desde Android 6 devuelve 02:00:00:00:00:00
        BT_Name_TextView.setText("BT Name: " + BT_name);
        BT_MAC_TextView.setText("BT MAC: " + BT_MAC);
        Log.d(TAG, "Nombre: " + BT_name + "  MAC: " + BT_MAC);
    }

    // =================================================================
    //  ESTADOS DE LA INTERFAZ
    // =================================================================

    /*
     * Sin conexión: solo se puede pulsar ACCEPT.
     * Lo usaremos al arrancar y al cerrar la conexión.
     */
    private void estadoNoConectado() {
        acceptConnectionButton.setEnabled(true);
        closeConnectionButton.setEnabled(false);
        sendTextButton.setEnabled(false);
        sendTextEditText.setEnabled(false);
        BT_ConnectionState_TextView.setText("NOT CONNECTED");
        Log.d(TAG, "Estado: NOT CONNECTED");
    }

    /*
     * Esperando o conectado: ACCEPT desactivado, el resto activado.
     * Lo usaremos al pulsar ACCEPT.
     */
    private void estadoConectado() {
        acceptConnectionButton.setEnabled(false);
        closeConnectionButton.setEnabled(true);
        sendTextButton.setEnabled(true);
        sendTextEditText.setEnabled(true);
    }

    // =================================================================
    //  CIERRE DE LA PANTALLA
    // =================================================================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (btServerManager != null) {
            btServerManager.cancelar();   // si no, el hilo seguiría vivo tras cerrar la app
        }
    }
}