package com.qwe7002.telegram_sms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class chat_long_polling_service extends Service {
    int offset = 0;
    int magnification = 1;
    int error_magnification = 1;
    String chat_id;
    String bot_token;
    Context context;

    public chat_long_polling_service() {

    }

    @SuppressLint("WrongConstant")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = public_func.get_notification_obj(getApplicationContext(), getString(R.string.chat_command_service_name));
        startForeground(2, notification);
        return START_STICKY;

    }


    public String get_network_type(Context context) {
        String net_type = "Unknown";
        ConnectivityManager connect_manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network_info = connect_manager.getActiveNetworkInfo();
        if (network_info == null) {
            return net_type;
        }
        switch (network_info.getType()) {
            case ConnectivityManager.TYPE_WIFI:
                net_type = "WIFI";
                break;
            case ConnectivityManager.TYPE_MOBILE:
                switch (network_info.getSubtype()) {
                    case TelephonyManager.NETWORK_TYPE_LTE:
                        net_type = "4G";
                        break;
                    case TelephonyManager.NETWORK_TYPE_EVDO_0:
                    case TelephonyManager.NETWORK_TYPE_EVDO_A:
                    case TelephonyManager.NETWORK_TYPE_EVDO_B:
                    case TelephonyManager.NETWORK_TYPE_EHRPD:
                    case TelephonyManager.NETWORK_TYPE_HSDPA:
                    case TelephonyManager.NETWORK_TYPE_HSPAP:
                    case TelephonyManager.NETWORK_TYPE_HSUPA:
                    case TelephonyManager.NETWORK_TYPE_HSPA:
                    case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                    case TelephonyManager.NETWORK_TYPE_UMTS:
                        net_type = "3G";
                        break;
                    case TelephonyManager.NETWORK_TYPE_GPRS:
                    case TelephonyManager.NETWORK_TYPE_EDGE:
                    case TelephonyManager.NETWORK_TYPE_CDMA:
                    case TelephonyManager.NETWORK_TYPE_1xRTT:
                    case TelephonyManager.NETWORK_TYPE_IDEN:
                        net_type = "2G";
                        break;
                }
        }
        return net_type;
    }

    public int get_active_card(Context context) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return -1;
        }
        return SubscriptionManager.from(context).getActiveSubscriptionInfoCount();
    }

    public int get_card2_subid(Context context) {
        int active_card = get_active_card(context);
        if (active_card >= 2) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return -1;
            }
            return SubscriptionManager.from(context).getActiveSubscriptionInfoForSimSlotIndex(1).getSubscriptionId();
        }
        return -1;
    }

    void start_long_polling(int offset_update_id) throws IOException {
        OkHttpClient okhttp_test_client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .build();
        Request request_test = new Request.Builder().url("https://api.telegram.org").build();
        Call call_test = okhttp_test_client.newCall(request_test);

        try {
            if (!public_func.check_network(context)) {
                throw new IOException("Network");
            }
            call_test.execute();
            error_magnification = 1;
        } catch (IOException e) {
            int sleep_time = 60 * error_magnification;

            public_func.write_log(context, "No network service,try again after " + sleep_time + " seconds");

            magnification = 1;
            if (error_magnification <= 4) {
                error_magnification++;
            }
            try {
                Thread.sleep(sleep_time * 1000);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
            return;

        }
        if (magnification <= 9) {
            magnification++;
        }

        int read_timeout = 30 * magnification;
        OkHttpClient okhttp_client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout((read_timeout + 5), TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        String request_uri = "https://api.telegram.org/bot" + bot_token + "/getUpdates";
        polling_json request_body = new polling_json();
        request_body.offset = offset_update_id;
        request_body.timeout = read_timeout;
        Gson gson = new Gson();
        RequestBody body = RequestBody.create(public_func.JSON, gson.toJson(request_body));
        Request request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(request);
        Response response = call.execute();
        if (response != null && response.code() == 200) {
            assert response.body() != null;
            JsonObject result_obj = new JsonParser().parse(response.body().string()).getAsJsonObject();
            if (result_obj.get("ok").getAsBoolean()) {
                JsonArray result_array = result_obj.get("result").getAsJsonArray();
                for (JsonElement item : result_array) {
                    handle(item.getAsJsonObject());
                }
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        final SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);

        chat_id = sharedPreferences.getString("chat_id", "");
        bot_token = sharedPreferences.getString("bot_token", "");
        assert bot_token != null;
        if (bot_token.isEmpty() || chat_id.isEmpty()) {
            stopForeground(true);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        start_long_polling(offset);
                        Thread.sleep(100);
                    } catch (IOException e) {
                        if (magnification > 1) {
                            magnification--;
                        }
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        break;
                    }
                }
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    void handle(JsonObject result_obj) {
        int update_id = result_obj.get("update_id").getAsInt();
        if (update_id >= offset) {
            offset = update_id + 1;
        }
        final request_json request_body = new request_json();
        request_body.chat_id = chat_id;
        JsonObject message_obj = null;
        if (result_obj.has("message")) {
            message_obj = result_obj.get("message").getAsJsonObject();
        }
        if (result_obj.has("channel_post")) {
            message_obj = result_obj.get("channel_post").getAsJsonObject();
        }
        if (message_obj == null) {
            //Reject group request
            public_func.write_log(context, "Request type is not allowed by security policy");
            return;
        }
        JsonObject from_obj = null;
        if (message_obj.has("from")) {
            from_obj = message_obj.get("from").getAsJsonObject();
        }
        if (message_obj.has("chat")) {
            from_obj = message_obj.get("chat").getAsJsonObject();
        }

        assert from_obj != null;
        String from_id = from_obj.get("id").getAsString();
        if (!Objects.equals(chat_id, from_id)) {
            public_func.write_log(context, "Chat ID[" + from_id + "] not allow");
            return;
        }

        String command = "";
        String request_msg = "";
        if (message_obj.has("text")) {
            request_msg = message_obj.get("text").getAsString();
        }
        if (message_obj.has("entities")) {
            JsonArray entities_arr = message_obj.get("entities").getAsJsonArray();
            JsonObject entities_obj_command = entities_arr.get(0).getAsJsonObject();
            if (entities_obj_command.get("type").getAsString().equals("bot_command")) {
                int command_offset = entities_obj_command.get("offset").getAsInt();
                int command_end_offset = command_offset + entities_obj_command.get("length").getAsInt();
                command = request_msg.substring(command_offset, command_end_offset).trim().toLowerCase();
            }
        }

        public_func.write_log(context, "request command: " + command);
        switch (command) {
            case "/start":
                request_body.text = getString(R.string.system_message_head) + "\n" + getString(R.string.available_command);
                break;
            case "/ping":
            case "/getinfo":
                BatteryManager batteryManager = (BatteryManager) context.getSystemService(BATTERY_SERVICE);
                request_body.text = getString(R.string.system_message_head) + "\n" + context.getString(R.string.current_battery_level) + batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "%\n" + getString(R.string.current_network_connection_status) + get_network_type(context);
                break;
            case "/sendsms":
            case "/sendsms2":
                request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.command_format_error);
                String[] msg_send_list = request_msg.split("\n");
                if (msg_send_list.length > 2) {
                    String msg_send_to = msg_send_list[1].trim().replaceAll(" ", "");
                    if (public_func.is_numeric(msg_send_to)) {
                        StringBuilder msg_send_content = new StringBuilder();
                        for (int i = 2; i < msg_send_list.length; i++) {
                            if (msg_send_list.length != 3 && i != 2) {
                                msg_send_content.append("\n");
                            }
                            msg_send_content.append(msg_send_list[i]);
                        }
                        String display_to_address = msg_send_to;
                        String display_to_name = public_func.get_phone_name(context, display_to_address);
                        if (display_to_name != null) {
                            display_to_address = display_to_name + "(" + msg_send_to + ")";
                        }
                        switch (command) {
                            case "/sendsms":
                                String dual_card = "";
                                if (get_active_card(context) >= 2) {
                                    dual_card = "SIM1 ";
                                }
                                public_func.send_sms(msg_send_to, msg_send_content.toString(), -1);
                                request_body.text = "[" + dual_card + context.getString(R.string.send_sms_head) + "]" + "\n" + context.getString(R.string.to) + display_to_address + "\n" + context.getString(R.string.content) + msg_send_content.toString();
                                break;
                            case "/sendsms2":
                                int sub_id = get_card2_subid(context);
                                request_body.text = "[" + context.getString(R.string.send_sms_head) + "]" + "\n" + getString(R.string.cant_get_card_2_info);
                                if (sub_id != -1) {
                                    public_func.send_sms(msg_send_to, msg_send_content.toString(), sub_id);
                                    request_body.text = "[SIM2 " + context.getString(R.string.send_sms_head) + "]" + "\n" + context.getString(R.string.to) + display_to_address + "\n" + context.getString(R.string.content) + msg_send_content.toString();


                                }
                                break;
                        }
                    }
                }
                break;
            default:
                request_body.text = context.getString(R.string.system_message_head) + "\n" + getString(R.string.unknown_command);
                break;
        }

        String request_uri = "https://api.telegram.org/bot" + bot_token + "/sendMessage";
        Gson gson = new Gson();
        RequestBody body = RequestBody.create(public_func.JSON, gson.toJson(request_body));
        OkHttpClient okhttp_client = public_func.get_okhttp_obj();
        Request send_request = new Request.Builder().url(request_uri).method("POST", body).build();
        Call call = okhttp_client.newCall(send_request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Looper.prepare();
                String error_message = "Send SMS Error:" + e.getMessage();
                public_func.write_log(context, error_message);
                Toast.makeText(context, error_message, Toast.LENGTH_SHORT).show();
                Looper.loop();
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() != 200) {
                    Looper.prepare();
                    assert response.body() != null;
                    String error_message = "Send SMS Error:" + response.body().string();
                    public_func.write_log(context, error_message);
                    Toast.makeText(context, error_message, Toast.LENGTH_SHORT).show();
                    Looper.loop();
                }
            }
        });
    }
}

class polling_json {
    int offset;
    int timeout;
}