import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;

/*  Первый вариант решения задачи.
    Все потоки пытаются пройти светофор с количеством пермитов requestLimit.
    Если поток получил пермит, он проходит к процедуре отправки запроса,
    а потом еще спит до истечения времени timeUnit, начиная от начала посылки запроса,
    запрещая ожидающим потокам пройти через светофор до окончания своего сна.

    Недостатком этого варианта является задержка после посылки, во время которой поток "своим телом"
    закрывает проход через семафор.
    Избежать этой задержки, даже если последующих потоков нет, нельзя. Текущий поток не знает,
    вдруг в последний момент создаваемого им окна нагрянет толпа страждущих послать запрос потоков
    и светофор должен отделить из них строго limit-1, а еще следующий - задержать.
 */

public class CrptApiWithSemaphore { //все поля можно сделать финальными для надежности
    private TimeUnit timeUnit; //инициализируется в конструкторе
    private ObjectMapper objectMapper = new ObjectMapper();
    private Semaphore semaphore; //количество пермитов задается в конструкторе при создании семафора
    private Logger logger = Logger.getLogger(CrptApiWithSemaphore.class);
    private String URL = "https://ismp.crpt.ru/api/v3/lk/documents/create"; //может быть параметром конструктора
    private HttpRequest.Builder builder = HttpRequest
            .newBuilder().uri(URI.create(URL)).timeout(Duration.ofSeconds(10));
    private HttpClient client = HttpClient.newHttpClient();

    public CrptApiWithSemaphore(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        semaphore = new Semaphore(requestLimit, true); //справедливая очередь
    }

    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ.
     * Документ и подпись должны передаваться в метод в виде Java объекта и строки соответственно.
     */
    public void createDoc(Doc doc, String signature)
            throws InterruptedException, IOException {
        String threadName = currentThread().getName();
        logger.debug(LocalTime.now() + " Вызов метода createDoc из потока " + threadName);
        //превращаем объект-параметр в json строку
        String json = objectMapper.writeValueAsString(doc);
        logger.trace("json = " + json);

        //готовим запрос в синхронизированном блоке, т.к. методы builder - не синхронизированы
        //и разные потоки могут помешать друг другу
        HttpRequest request;
        synchronized (this) {
            request = builder.header("signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        }
        logger.debug(LocalTime.now() + " Поток " + threadName + " - перед семафором");

        //запрашиваем пермит
        semaphore.acquire();
        try {
            logger.debug(LocalTime.now() + " Поток " + threadName + " - прошел семафор и начинает посылку запроса");
            long startTime = System.currentTimeMillis();
            //посылаем запрос
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            logger.trace("response.body() = " + response.body()); //приходит ошибка аутенификакции
            logger.debug(LocalTime.now() + " Поток " + threadName + " послал запрос");
            long endTime = System.currentTimeMillis();

            //Теперь задача - захватить пермит светофора на время timeUnit,
            //чтобы не допустить входа следующего потока после limit-1 следующих за текущим потоков.
            //Захватывая пермит на период своего окна timeUnit и учитывая, что остальных пермитов limit-1,
            //мы можем гарантировать, что в окне, образованном текущим потоком, будет не больше limit посылок.
            //Часть времени потрачено на посылку. Остальное время - досыпаем, чтобы не освобождать пермит.
            long waitingTime = timeUnit.toMillis(1) - endTime + startTime;
            if (waitingTime > 0) {
                sleep(waitingTime);
            }
            logger.debug(LocalTime.now() + " Метод createDoc закончил работу в потоке " + threadName);
        } finally {   //освобождаем пермит светофора штатно или в случае ошибки
            semaphore.release();
        }
    }
}



