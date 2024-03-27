import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;

import java.util.concurrent.atomic.AtomicLongArray;

/*  Второй вариант решения задачи.
    Времена посылки запросов запоминаются в кольцевом массиве емкостью requestLimit.
    Очередной поток проверяет время посылки для самого старого запроса и засыпает,
    пока не истечет время timeUnit со времени той посылки. Затем посылает запрос и уходит.

    В этом варианте, если потоки приходят редко,
    они нисколько не задерживаются, немедленно приступают к посылке и уходят без задержки.
    Однако, если существует очередь, то поток должен отстоять ее всю.
 */

public class CrptApiWithSynchronizedBlock { //все поля можно сделать финальными для надежности
    private TimeUnit timeUnit; //инициализируется в конструкторе
    private ObjectMapper objectMapper = new ObjectMapper();
    //чтобы сделать ячейки массива волатильными используем не просто long[], а AtomicLongArray
    private AtomicLongArray sendTimes; //массив для хранения времен отправок. Записывается по кольцу
    private int lastSendTimeIndex = 0; //индекс ячейки массива, хранящей последнюю отправку

    private int nextIndex(int index) { //индекс следующей ячейки массива. Считаем по кольцу
        return (index == sendTimes.length() - 1) ? 0 : index + 1;
    }

    private Logger logger = Logger.getLogger(CrptApiWithSynchronizedBlock.class);
    private String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create"; //может быть параметром конструктора
    private HttpRequest.Builder builder = HttpRequest
            .newBuilder().uri(URI.create(URL)).timeout(Duration.ofSeconds(10));
    private HttpClient client = HttpClient.newHttpClient();

    public CrptApiWithSynchronizedBlock(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        //Размер массива для хранения времен отправок равен
        //разрешенному количеству отправок в единицу времени.
        sendTimes = new AtomicLongArray(requestLimit);
    }

    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ.
     * Документ и подпись должны передаваться в метод в виде Java объекта и строки соответственно.
     */
    public void createDoc(Doc doc, String signature) throws InterruptedException, IOException {
        String threadName = currentThread().getName();
        logger.debug(LocalTime.now() + " Вызов метода createDoc из потока " + threadName);
        //превращаем объект-параметр в json строку
        String json = objectMapper.writeValueAsString(doc);

        //Готовим запрос в синхронизированном блоке, т.к. методы builder - не синхронизированы
        //и разные потоки могут помешать друг другу
        //Используем мьютекс любого произвольного общего объекта, чтобы разрешить другим потокам
        //исполнять этот блок во время отправки запроса
        HttpRequest request;
        synchronized (timeUnit) {
            request = builder.POST(HttpRequest.BodyPublishers.ofString(json)).build();
        }
        logger.debug(LocalTime.now() + " Поток " + threadName + " - перед синхронизированным блоком");

        //если нужна справедливая отправка, то вместо синхронизированого блока
        //нужно использовать светофор с одним пермитом и fair=true
        synchronized (this) {
            //Синхронизированный блок нужен не только чтобы допустить к отправке с задержкой только один поток
            //но и для правильного расчета задержки, который обращается к общему массиву для чтения и записи
            logger.debug(LocalTime.now() + " Поток " + threadName + " - захватил мьютекс");
            //Определяем величину задержки перед отправкой,
            //требуемой для соблюдения количества отправок в единицу времени.
            //Для этого посмотрим на ячейку массива, следующую после последней отправки
            //Там лежит время отправки запроса, отправленного  requestLimit попыток назад от текущего.
            //Это самое старое время из всех хранимых в массиве (из-за кольцевой формы его записи)
            //Текущая отправка должна произойти не раньше, чем TimeUnit после этого времени.
            long elapsedTime = System.currentTimeMillis() - sendTimes.get(nextIndex(lastSendTimeIndex));
            long waitingTime = timeUnit.toMillis(1) - elapsedTime;

            if (waitingTime > 0) {
                sleep(waitingTime);
            }
            logger.debug(LocalTime.now() + " Поток " + threadName + " - допущен к отправке запроса."
                    + "Ожидание составило - " + ((waitingTime > 0) ? waitingTime : 0) + " мс");
            //сдвигаем вперед указатель элемента массива для последней посылки запроса
            lastSendTimeIndex = nextIndex(lastSendTimeIndex);
            //записываем в массив время допуска к посылке запроса
            sendTimes.set(lastSendTimeIndex, System.currentTimeMillis());
        }  //конец синхронизированного блока
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        logger.debug(LocalTime.now() + " Метод createDoc послал запрос из потока " + threadName + " и закончил работу");
    }
}

