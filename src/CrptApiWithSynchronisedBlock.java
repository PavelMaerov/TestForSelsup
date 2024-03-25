import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;

public class CrptApiWithSynchronisedBlock {
    private TimeUnit timeUnit;
    private ObjectMapper objectMapper;
    private long[] sendTimes; //массив для хранения времен отправок. Записывается по кольцу
    private int lastSendTimeIndex = 0; //индекс ячейки массива, хранящей последнюю отправку

    private int nextIndex(int index) { //индекс следующей ячейки массива. Считаем по кольцу
        return (index == sendTimes.length - 1) ? 0 : index + 1;
    }

    private HttpClient client;

    public CrptApiWithSynchronisedBlock(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        objectMapper = new ObjectMapper();
        //Размер массива для хранения времен отправок равен
        //разрешенному количеству отправок в единицу времени.
        sendTimes = new long[requestLimit];
        client = HttpClient.newHttpClient();
    }

    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ.
     * Документ и подпись должны передаваться в метод в виде Java объекта и строки соответственно.
     */
    public void createDoc(Doc doc, String signature) throws InterruptedException {
        String threadName = currentThread().getName();
        System.out.println(LocalTime.now() + " Вызов метода createDoc из потока " + threadName);
        String JSON;
        try {
            JSON = objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            //для тестового задания не подключаю logger
            System.out.println(e.getMessage());
            return;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create_"))
                .POST(HttpRequest.BodyPublishers.ofString(JSON))
                .build();
        System.out.println(LocalTime.now() + " Поток " + threadName + " - перед мьютексом");
        //если нужна справедливая отправка, то вместо синхронизированого блока
        //нужно использовать светофор с одним пермитом и fair=true
        synchronized (this) {
            System.out.println(LocalTime.now() + " Поток " + threadName + " - захватил мьютекс");
            //Определяем величину задержки перед отправкой,
            //требуемой для соблюдения количества отправок в единицу времени.
            //Для этого посмотрим на ячейку массива, следующую после последней отправки
            //Там лежит время отправки запроса, отправленного  requestLimit попыток назад от текущего.
            //Это самое старое время из всех хранимых в массиве (из-за кольцевой формы его записи)
            //Текущая отправка должна произойти не раньше, чем TimeUnit после этого времени.
            long elapsedTime = System.currentTimeMillis() - sendTimes[nextIndex(lastSendTimeIndex)];
            long waitingTime = timeUnit.toMillis(1) - elapsedTime;

            //Здесь время процесса посылки запроса выведено из времени захвата мьютекса
            //Доступ к отправке определяется количеством моментов начала отправок в единицу времени
            //и не зависит от времени процесса отправки
            if (waitingTime > 0) {
                sleep(waitingTime);
            }
            System.out.println(LocalTime.now() + " Поток " + threadName + " - допущен к отправке запроса."
                    + "Ожидание составило - "+((waitingTime > 0)?waitingTime:0)+" мс");
            lastSendTimeIndex = nextIndex(lastSendTimeIndex);
            sendTimes[lastSendTimeIndex] = System.currentTimeMillis();
        }  //конец синхронизированного блока
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            System.out.println(e.getMessage());
            return;
        }
        System.out.println(LocalTime.now() + " Метод createDoc послал запрос из потока " + threadName + " и закончил работу");
    }
}

