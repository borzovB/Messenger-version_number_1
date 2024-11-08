package org.face_recognition;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;

public class TheServer {
    private static final int PORT = 8080;
    private static final String URL = "jdbc:postgresql://localhost:5432/";
    // Имя пользователя и пароль для подключения к PostgreSQL
    private static final String USER = "postgres";
    private static final String PASSWORD = "123";
    private static String dbName = "testdb";
    private static String tableName = "employees";
    private static final Map<String, ObjectOutputStream> clientStreams = new HashMap<>();

    public static void main(String[] args) {
        new Thread(TheServer::startServer).start();
    }

    private static void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Сервер запущен и ожидает подключения...");
            initializeDatabase(); // Не нужно в поток

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Клиент подключен");

                // Проверка на null clientSocket и задержка по времени, предотвращение Dos
                if (clientSocket != null) {
                    new Thread(() -> handleClient(clientSocket)).start(); // В отдельный поток
                }
            }
        } catch (IOException e) {
            System.out.println("Ошибка при запуске сервера: " + e.getMessage());
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
             ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream())) {

            Object command;

            while ((command = in.readObject()) != null) {
                if (command instanceof String) {
                    String strCommand = (String) command;
                    if (/*"GET_EMPLOYEES".equals(strCommand)*/strCommand.startsWith("GET_EMPLOYEES")) {
                        String [] emp = strCommand.split(" ");
                        String result = sendEmployeeData(emp, "name","password");
                        System.out.println(result);
                        out.writeObject(result);// отправка данных о сотрудниках
                        System.out.println("Отправлены данные сотрудников");
                    } else if (strCommand.startsWith("UPDATE_PASSWORD")) {
                        String[] update_password = strCommand.split(" ");
                        System.out.println(update_password[1]);
                        if (update_password.length == 4) {
                            System.out.println("1) " + update_password[1]);
                            String result = sendEmployeeData(update_password, "name","email");
                            System.out.println("ID "+result);
                            if(!result.equals("NULL")){
                                updateEmployeePassword(result, update_password[3], out);
                            }else {
                                out.writeObject("0");
                            }

                        } else {
                            out.writeObject("Неверная команда. Формат должен быть: UPDATE_PASSWORD");
                        }
                    }else{
                        if(strCommand.startsWith("NAME")){

                            String[] clientName = strCommand.split(" ");
                            System.out.println(clientName[1]);
                            synchronized (clientStreams) {
                                clientStreams.put(clientName[1], out);
                            }
                            printClientStreamsDetailed();

                        }else {
                            if(strCommand.startsWith("EXIT")){
                                String[] clientNameExit = strCommand.split(" ");
                                System.out.println(clientNameExit[1]);
                                synchronized (clientStreams) {
                                    clientStreams.remove(clientNameExit[1]);
                                }
                                System.out.println("Клиент " + clientNameExit[1] + " вышел.");
                            }else{
                                if(strCommand.startsWith("ONLINE")){
                                    String[] clientOnline = strCommand.split(" ");
                                    if (clientStreams.containsKey(clientOnline[1])) {
                                        out.writeObject("1");
                                    }else {
                                        out.writeObject("0");
                                    }
                                }else {
                                    if(strCommand.startsWith("UNIQUENESS")){
                                        String[] update = strCommand.split(" ");
                                        String result = sendEmployeeDataUniqueness(update[1], "name")+" "+sendEmployeeDataUniqueness(update[2], "email");
                                        out.writeObject(result);
                                    }
                                }
                            }
                        }
                    }
                } else if (command instanceof String[]) {  // убедитесь, что данные являются массивом строк
                    String[] parts = (String[]) command;
                    if (parts.length == 3) {
                        System.out.println("Получен массив строк:");
                        System.out.println(parts[0]);  // username
                        System.out.println(parts[1]);  // email
                        System.out.println(parts[2]);  // password
                        inserting_data_into_table(parts);  // Вставка данных в таблицу
                        out.writeObject("Пользователь успешно добавлен");
                    } else {
                        out.writeObject("Неверный формат данных");
                    }
                }else {
                    if (command instanceof FileTransferData) {
                        handleFileTransfer((FileTransferData) command);
                    }
                }
            }

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    // Метод для обработки передачи файла от одного клиента другому.
    private static void handleFileTransfer(FileTransferData data) {
        // Синхронизируем блок для потокобезопасного доступа к карте потоков клиентов.
        synchronized (clientStreams) {

            // Ищем поток вывода клиента-получателя по его имени (из объекта FileTransferData).
            ObjectOutputStream recipientOut = clientStreams.get(data.getRecipientName());
            // Если получатель найден (он подключен), отправляем ему файл.
            if (recipientOut != null) {
                try {
                    recipientOut.writeObject("HI");
                    System.out.println(recipientOut);
                    // Отправляем объект FileTransferData получателю.
                    recipientOut.writeObject(data);
                    System.out.println("Файл отправлен");
                } catch (IOException e) {
                    // Если произошла ошибка при отправке, выводим сообщение.
                    System.out.println("Ошибка при отправке файла клиенту: " + e.getMessage());
                }
            }
        }
    }

    public static void printClientStreamsDetailed() {
        synchronized (clientStreams) {  // Для потокобезопасности
            if (clientStreams.isEmpty()) {
                System.out.println("Нет подключенных клиентов.");
            } else {
                System.out.println("Подключенные клиенты и их потоки:");
                for (Map.Entry<String, ObjectOutputStream> entry : clientStreams.entrySet()) {
                    String clientName = entry.getKey();
                    ObjectOutputStream stream = entry.getValue();
                    System.out.println("Клиент: " + clientName + ", Поток: " + stream);
                }
            }
        }
    }

    private static String sendEmployeeDataUniqueness(String inputData, String field_number_1) {
        String query = "SELECT name_id, name, email, password FROM employees";
        String dbName = "testdb"; // Ensure this is declared if not already
        Argon2 argon2 = Argon2Factory.create();

        try (Connection connection = DriverManager.getConnection(URL + dbName, USER, PASSWORD);
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {

            ResultSet resultSet = preparedStatement.executeQuery();
            boolean found = false; // Flag to check if an employee was found
            String result = null;

            while (resultSet.next()) {
                String name = resultSet.getString(field_number_1);

                if (argon2.verify(name, inputData.toCharArray())) {
                    result = "false"; // Send the nameId
                    found = true; // Set the flag to true
                    break; // Exit the loop since we found a match
                }
            }

            // If no employee was found, send "NULL"
            if (!found) {
                result = "true";
            }

            return result;

        } catch (SQLException e) {
            e.printStackTrace();
            return "Erro";
        }
    }

    private static String sendEmployeeData(String[] inputData, String field_number_1, String field_number_2) {
        String query = "SELECT name_id, name, email, password FROM employees";
        String dbName = "testdb"; // Ensure this is declared if not already
        Argon2 argon2 = Argon2Factory.create();

        try (Connection connection = DriverManager.getConnection(URL + dbName, USER, PASSWORD);
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {

            ResultSet resultSet = preparedStatement.executeQuery();
            boolean found = false; // Flag to check if an employee was found
            String result = null;

            while (resultSet.next()) {
                String nameId = resultSet.getString("name_id");
                String name = resultSet.getString(field_number_1);
                String password = resultSet.getString(field_number_2);

                // Проверка имени и пароля на клиенте
                if (argon2.verify(name, inputData[1].toCharArray()) &&
                        argon2.verify(password, inputData[2].toCharArray())) {
                    result = nameId; // Send the nameId
                    found = true; // Set the flag to true
                    break; // Exit the loop since we found a match
                }
            }

            // If no employee was found, send "NULL"
            if (!found) {
                result = "NULL";
            }

            return result;

        } catch (SQLException e) {
            e.printStackTrace();
            return "Erro";
        }
    }

    public static void updateEmployeePassword(String nameID, String newPassword, ObjectOutputStream out) {
        String query = "UPDATE employees SET password = ? WHERE name_id = ?";
        dbName = "testdb";

        System.out.println(newPassword);

        try (Connection connection = DriverManager.getConnection(URL + dbName, USER, PASSWORD);
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {

            preparedStatement.setString(1, newPassword);
            preparedStatement.setString(2, nameID);

            int rowsAffected = preparedStatement.executeUpdate();

            if (rowsAffected > 0) {
                out.writeObject("1");
                System.out.println("1");
            } else {
                out.writeObject("0");
            }

        } catch (SQLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void initializeDatabase() {
        dbName = "testdb";
        tableName = "employees";

        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {

            if (!doesDatabaseExist(conn, dbName)) {
                String createDBQuery = "CREATE DATABASE " + dbName;
                stmt.executeUpdate(createDBQuery);
                System.out.println("База данных " + dbName + " создана.");
            } else {
                System.out.println("База данных " + dbName + " уже существует.");
            }

            try (Connection newConn = DriverManager.getConnection(URL + dbName, USER, PASSWORD);
                 Statement newStmt = newConn.createStatement()) {

                if (!doesTableExist(newConn, tableName)) {
                    String createTableQuery = "CREATE TABLE employees (" +
                            "id SERIAL PRIMARY KEY, " +
                            "name_id VARCHAR(100) UNIQUE, " +
                            "name VARCHAR(100) NOT NULL, " +
                            "email VARCHAR(100) UNIQUE NOT NULL, " +
                            "password VARCHAR(255) NOT NULL" +
                            ")";
                    newStmt.executeUpdate(createTableQuery);
                    System.out.println("Таблица " + tableName + " создана.");
                } else {
                    System.out.println("Таблица " + tableName + " уже существует.");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static void inserting_data_into_table(String[] parts) {
        String newClientId = generateClientId();
        System.out.println("Сгенерированный ID клиента: " + newClientId);

        String insertQuery = "INSERT INTO employees (name_id, name, email, password) VALUES (?, ?, ?, ?)";

        try (Connection connection = DriverManager.getConnection(URL + "testdb", USER, PASSWORD);
             PreparedStatement preparedStatement = connection.prepareStatement(insertQuery)) {

            preparedStatement.setString(1, newClientId);
            preparedStatement.setString(2, parts[0]);
            preparedStatement.setString(3, parts[1]);
            preparedStatement.setString(4, parts[2]);
            preparedStatement.executeUpdate();
            System.out.println("Данные для первого сотрудника успешно вставлены.");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static String generateClientId() {
        UUID clientId = UUID.randomUUID();
        return clientId.toString();
    }

    private static boolean doesDatabaseExist(Connection conn, String dbName) {
        String query = "SELECT 1 FROM pg_database WHERE datname = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, dbName);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean doesTableExist(Connection conn, String tableName) {
        String query = "SELECT EXISTS (" +
                "SELECT 1 FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }
}
