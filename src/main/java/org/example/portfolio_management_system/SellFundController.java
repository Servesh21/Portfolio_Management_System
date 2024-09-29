

package org.example.portfolio_management_system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.LocalDate;

public class SellFundController {

    @FXML
    private TextField fundSearchField;  // Field to search for the fund
    @FXML
    private TextField unitsToSellField; // Field to input units to sell
    @FXML
    private Label statusLabel;          // Label to show sale status
    @FXML
    private ListView<String> fundListView; // ListView to show fund suggestions
    @FXML
    private Label unitsHeldLabel;// Label to show the number of units held by the client
    private Stage stage;
    private Scene scene;
    private Parent root;



    // Initialize the controller
    @FXML
    public void initialize() {
        fundSearchField.textProperty().addListener((observable, oldValue, newValue) -> searchFunds(newValue));
        fundListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, selectedFund) -> {
            if (selectedFund != null) {
                displayUnitsHeld(selectedFund);
            }
        });
    }

    // Handle the sell action when the user submits the form
    @FXML
    public void handleSellAction()  {
        String fundName = fundListView.getSelectionModel().getSelectedItem();
        String sipid = fundSearchField.getText();
    // Get the selected fund name
        if (fundName == null) {
            statusLabel.setText("Please select a fund.");
            return;
        }

        String unitsToSellStr = unitsToSellField.getText();
        if (unitsToSellStr == null || unitsToSellStr.isEmpty()) {
            statusLabel.setText("Please enter units to sell.");
            return;
        }

        try {
            double unitsToSell = Double.parseDouble(unitsToSellStr);
            double currentUnits = getCurrentUnitsForFund(fundName);
            if (unitsToSell > currentUnits) {
                statusLabel.setText("You do not have enough units to sell.");
                return;
            }

            double nav = getCurrentNavForFund(fundName);
            double saleAmount = unitsToSell * nav;

            // Update the portfolio and store the sale transaction
            updatePortfolioAfterSale(fundName,sipid, unitsToSell, saleAmount);
            storeSaleTransaction(saleAmount, unitsToSell, fundName);

            statusLabel.setText(String.format("Successfully sold %.2f units for ₹%.2f", unitsToSell, saleAmount));

        } catch (NumberFormatException e) {
            statusLabel.setText("Invalid units entered.");
        } catch (SQLException e) {
            e.printStackTrace();
            statusLabel.setText("Error during fund sale.");
        }
        Stage stage = (Stage) statusLabel.getScene().getWindow();


//        stage.close();

        try {
            reloadSIPManagementScreen();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
    private void reloadSIPManagementScreen() throws IOException {
        // Load the FXML file
        FXMLLoader loader = new FXMLLoader(getClass().getResource("SIPManagement.fxml"));

        Parent newRoot = loader.load();
        // Get the current stage
        Stage currentStage = (Stage) statusLabel.getScene().getWindow();
        if (currentStage != null) {
            // Create a new Scene and set it to the current stage
            currentStage.setScene(new Scene(newRoot));
            currentStage.show(); // Make sure to show the stage
        } else {
            System.out.println("Current stage is null");
        }
    }


    // Method to search funds and update ListView
    private void searchFunds(String query) {
        ObservableList<String> fundItems = FXCollections.observableArrayList();
        if (query == null || query.isEmpty()) {
            fundListView.setItems(fundItems);
            return;
        }

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT fund_Name FROM sip WHERE fund_Name LIKE ?")) {
            stmt.setString(1, "%" + query + "%");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                fundItems.add(rs.getString("fund_name"));
            }
            fundListView.setItems(fundItems);
        } catch (SQLException e) {
            e.printStackTrace();
            statusLabel.setText("Error fetching fund list.");
        }
    }

    // Fetch the current units for the selected fund from the portfolio
    private double getCurrentUnitsForFund(String fundName) throws SQLException {
        String query = "SELECT total_units, sip_amount FROM sip WHERE fund_Name = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, fundName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("total_units");
            } else {
                throw new SQLException("Fund not found in portfolio.");
            }
        }
    }

    // Fetch the current NAV for the selected fund from the API
    private double getCurrentNavForFund(String fundName) {
        String fundId = getFundIdFromName(fundName); // Implement this method to map fundName to fundId
        if (fundId == null) {
            return 0.0;
        }
        return fetchNavForFund(fundId);
    }

    // Fetch NAV from API
    private double fetchNavForFund(String fundId) {
        String navApiUrl = "https://api.mfapi.in/mf/" + fundId;
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(navApiUrl))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String jsonResponse = response.body();

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode navNode = rootNode.path("data").get(0).path("nav");

            return navNode.asDouble();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        return 0.0;
    }

    // Update the portfolio after selling the units
    private void updatePortfolioAfterSale(String fundName,String fund,double unitsToSell, double saleAmount) throws SQLException {
        String updateSQL = "UPDATE sip SET total_units = total_units - ?, sip.sip_amount = sip_amount - ? WHERE fund_name = ? AND total_units >= ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(updateSQL)) {
            int userid = getCurrentUserId();
            double totalUnits=0;
            updatePortfolio(userid,fund,fundName,totalUnits-unitsToSell);
            stmt.setDouble(1, unitsToSell);
            stmt.setDouble(2, (unitsToSell * getCurrentNavForFund(fundName))); // Reduce invested amount
            stmt.setString(3, fundName);
            stmt.setDouble(4, unitsToSell);

            double rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0.00000000000) {
                throw new SQLException("Not enough units to sell.");
            }
        }

        // Optionally, remove the fund from portfolio if units become 0
        String deleteSQL = "DELETE FROM sip WHERE total_units = 0 AND fund_name = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(deleteSQL)) {
            stmt.setString(1, fundName);
            stmt.executeUpdate();
        }
    }

    // Store the sale transaction in the database
    private void storeSaleTransaction(double saleAmount, double unitsSold, String fundName) throws SQLException {
        String insertSQL = "INSERT INTO transactions (amount, units, type1, transaction_date, fund_name,fund_type) VALUES (?, ?, 'Sell', ?, ?,'SIP')";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertSQL)) {
            stmt.setDouble(1, saleAmount);
            stmt.setDouble(2, unitsSold);
            stmt.setDate(3, Date.valueOf(LocalDate.now()));
            stmt.setString(4, fundName);

            stmt.executeUpdate();
        }
    }

    // Placeholder method to get fund ID from fund name
    private String getFundIdFromName(String fundName) {
        String query = "SELECT fund_id FROM sip WHERE fund_name = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, fundName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("fund_id");
            } else {
                throw new SQLException("Fund ID not found for fund name: " + fundName);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void displayUnitsHeld(String fundName) {
        try {
            double unitsHeld = getCurrentUnitsForFund(fundName);
            unitsHeldLabel.setText(String.format("Units Held: %.2f", unitsHeld));
        } catch (SQLException e) {
            e.printStackTrace();
            unitsHeldLabel.setText("Error fetching units held.");
        }
    }
    private int getCurrentUserId() {
        return UserSession.getInstance().getUserId();
    }
    public void updatePortfolio(int userId, String fund_id, String fundname, double totalUnits) {
        try {
            Connection connection = DatabaseConnection.getConnection();

            // Get the total invested amount and current value for this user from mutual_funds
            String sumQuery = "SELECT SUM(amount_invested) AS totalAmountInvested, SUM(current_value) AS totalCurrentValue " +
                    "FROM mutual_funds WHERE user_id = ?";
            PreparedStatement ps = connection.prepareStatement(sumQuery);
            ps.setInt(1, userId);
            ResultSet resultSet = ps.executeQuery();

            if (resultSet.next()) {
                double totalAmountInvested = resultSet.getDouble("totalAmountInvested");
                double totalCurrentValue = resultSet.getDouble("totalCurrentValue");

                // Check if a portfolio entry for this user and fund already exists
                String checkQuery = "SELECT * FROM portfolio WHERE user_id = ? AND scheme_code = ?";
                PreparedStatement checkPs = connection.prepareStatement(checkQuery);
                checkPs.setInt(1, userId);
                checkPs.setString(2, fund_id);
                ResultSet checkResultSet = checkPs.executeQuery();

                if (checkResultSet.next()) {
                    // If entry exists, update the existing portfolio record
                    String updateQuery = "UPDATE portfolio SET amount_invested = ?, current_value = ?, units = ?, fund_name = ? " +
                            "WHERE user_id = ? AND scheme_code = ?";
                    PreparedStatement updatePs = connection.prepareStatement(updateQuery);
                    updatePs.setDouble(1, totalAmountInvested);
                    updatePs.setDouble(2, totalCurrentValue);
                    updatePs.setDouble(3, totalUnits);
                    updatePs.setString(4, fundname);
                    updatePs.setInt(5, userId);
                    updatePs.setString(6, fund_id);
                    updatePs.executeUpdate();
                    updatePs.close();
                } else {
                    // If no entry exists, you might want to insert a new record (optional)
                    String insertQuery = "INSERT INTO portfolio (user_id, amount_invested, current_value, type, scheme_code, fund_name, units) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)";
                    PreparedStatement insertPs = connection.prepareStatement(insertQuery);
                    insertPs.setInt(1, userId);
                    insertPs.setDouble(2, totalAmountInvested);
                    insertPs.setDouble(3, totalCurrentValue);
                    insertPs.setString(4, "SIP");
                    insertPs.setString(5, fund_id);
                    insertPs.setString(6, fundname);
                    insertPs.setDouble(7, totalUnits);
                    insertPs.executeUpdate();
                    insertPs.close();
                }

                checkResultSet.close();
                checkPs.close();
            }

            resultSet.close();
            ps.close();
            connection.close();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


}
