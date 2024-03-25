import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import static java.lang.Thread.currentThread;
import static java.lang.Thread.sleep;

public class CrptApiWithSemaphore {
    private TimeUnit timeUnit; //инициализируется в конструкторе
    private ObjectMapper objectMapper;
    private Semaphore semaphore; //количество пермитов задается в конструкторе при создании семафора
    private HttpClient client;

    public CrptApiWithSemaphore(TimeUnit timeUnit, int requestLimit) {
        this.timeUnit = timeUnit;
        objectMapper = new ObjectMapper();
        semaphore = new Semaphore(requestLimit, true); //справедливая очередь
        client = HttpClient.newHttpClient();
    }

    /**
     * Создание документа для ввода в оборот товара, произведенного в РФ.
     * Документ и подпись должны передаваться в метод в виде Java объекта и строки соответственно.
     */
    public void createDoc (Doc doc, String signature) throws InterruptedException {
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
        //System.out.println("JSON = " + JSON);

        //Вызывается по HTTPS метод POST следующий URL:
        //https://ismp.crpt.ru/api/v3/lk/documents/create
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create_"))
                .POST(HttpRequest.BodyPublishers.ofString(JSON))
                .build();
        System.out.println(LocalTime.now() + " Поток " + threadName + " - перед семафором");
        semaphore.acquire();
        System.out.println(LocalTime.now() + " Поток " + threadName + " - прошел семафор");

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            //System.out.println("response.body() = " + response.body());
            //response.body() в тестовом задании не анализируем);
        } catch (IOException e) {
            System.out.println(e.getMessage());
            return;
        }
        System.out.println(LocalTime.now() + " Метод createDoc послал запрос из потока " + threadName);
        //Сейчас пермит светофора захватывается на время посылки + величину задержки
        //Задержку можно уменьшить на время посылки.
        sleep(timeUnit.toMillis(1));
        System.out.println(LocalTime.now() + " Метод createDoc закончил работу в потоке " + threadName);
        semaphore.release();
    }
}

//Этот класс должен лежать в отдельном файле, также как и вложенные классы
//Помещаю класс Doc здесь только по требованию задания
//Последующие классы реализованы простейшим образом без геттеров и сеттеров
class INN {
    public String participantInn;
}

class Product {
    public String certificate_document;
    public LocalDate certificate_document_date;
    public String certificate_document_number;
    public String owner_inn;
    public String producer_inn;
    public LocalDate production_date;
    public String tnved_code;
    public String uit_code;
    public String uitu_code;
}

class Doc {
    public INN description;
    public String doc_id;
    public String doc_status;
    public String doc_type = "LP_INTRODUCE_GOODS";
    public boolean importRequest;
    public String owner_inn;
    public String participant_inn;
    public String producer_inn;
    public LocalDate production_date;
    public String production_type;
    public Product[] products;
    public LocalDate reg_date;
    public String reg_number;
}



